package com.tm.im.storage.media;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.domain.media.MediaStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 本地文件系统实现（开发与单机部署用；DESIGN §15.1 的「开发阶段先用本地 FS」）。
 *
 * <p><b>key → 路径的映射必须经过规范化与包含性校验</b>。key 是
 * {@code yyyy/MM/{id}.{ext}}，全部由服务端生成（id 是雪花号、ext 来自内容嗅探），
 * 所以按当下的调用方看，「路径穿越」不可能发生。仍然要校验，理由与
 * {@code MessageService} 里那些「看起来多余的判断」相同：<b>这个端口是公开的</b>，
 * 将来任何一个新的调用方都可能把用户输入直接当 key 传进来，而那时
 * {@code ../../etc/passwd} 就不再是理论问题。校验放在实现里，只写一次，
 * 且它的失败方式是「抛 40002」而不是「读到别的文件」。
 *
 * <p><b>写入用「临时文件 + 原子改名」</b>：先写 {@code xxx.tmp} 再
 * {@code ATOMIC_MOVE} 到目标名。直接写目标名的话，一个并发读请求
 * （客户端拿到 media_id 后立刻 GET）可能读到<b>只写了一半</b>的文件，
 * 而那个响应是 HTTP 200 + 一个坏掉的图片——客户端只会显示「图片损坏」。
 * 原子改名在同一个文件系统内是原子的，读者要么看到旧文件（不存在）要么看到完整的。
 *
 * <p>目录按 {@code yyyy/MM} 两级：单目录下几十万个文件会让 {@code ls} 与
 * 文件系统元数据操作变慢（ext4/NTFS 都是），而两级分桶把单目录规模压到
 * 「一个月上传量」这个可以接受的量级。
 */
@Component
public class LocalFsMediaStore implements MediaStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFsMediaStore.class);

    /** 落库的 {@code tm.storage.type} 取值之一。另外两个是预留（见 {@link StorageProperties#getType()}）。 */
    public static final String TYPE_LOCAL = "local";

    private final StorageProperties properties;

    /** 规范化后的根目录（绝对路径）。启动时算一次，避免每个请求都做一次 {@code toRealPath}。 */
    private Path root;

    public LocalFsMediaStore(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动校验：把「配了 oss 但没实现」「根目录不可创建」这两件事挡在启动期。
     *
     * <p>不这么做的话，前者会在第一次上传时才炸（而那时运维已经在看业务日志了），
     * 后者只会在有用户上传时炸。启动时失败是<b>最便宜</b>的失败——它发生在
     * 任何用户受到影响之前，且只有一个明确的原因。
     */
    @PostConstruct
    void init() {
        String type = properties.getType() == null ? "" : properties.getType().strip().toLowerCase();
        if (!TYPE_LOCAL.equals(type)) {
            throw new IllegalStateException(
                    "tm.storage.type=" + properties.getType() + " 尚未实现（当前只有 local）。"
                            + "留空/写错都会静默地按 local 跑，所以这里选择直接启动失败："
                            + "多实例部署下「图片各存各的机器」要在很久以后才会被发现。");
        }
        if (properties.getMediaRoot() == null || properties.getMediaRoot().isBlank()) {
            throw new IllegalStateException("tm.storage.media-root 不能为空");
        }
        try {
            root = Paths.get(properties.getMediaRoot()).toAbsolutePath().normalize();
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "媒体根目录不可创建/不可写: " + properties.getMediaRoot(), e);
        }
        log.info("媒体存储就绪 type=local root={}", root);
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(tmp, content);
            // ATOMIC_MOVE：读者永远看不到半个文件（见类注释）。
            // 不支持原子改名的文件系统（少数网络盘）会抛 AtomicMoveNotSupportedException，
            // 那时退化为普通 MOVE —— 后者在同一文件系统内也是「先删后建」的语义，
            // 仍有极小的窗口，但比直接写目标文件好得多，且这种情况本身就该被记录。
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                log.warn("文件系统不支持原子改名，退化为普通移动 key={}", key);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 兜底清理临时文件：不删的话，一次失败就会在盘上留下一个 .tmp，
            // 而它不会被任何清理任务认领（清理任务按 media 表的 key 反查）。
            quietlyDelete(tmp);
            throw new TmException(ErrorCode.STORAGE_UNAVAILABLE, "写入失败 key=" + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            // 读失败是「存储不可用」，不是「不存在」：前者可重试，后者不该重试。
            // 把两者混成一个 40008 会让客户端放弃一张真实存在的图片。
            throw new TmException(ErrorCode.STORAGE_UNAVAILABLE, "读取失败 key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // 删除失败只记日志、不抛：调用它的是「已有错误时的补偿」，
            // 因为补偿失败再抛一个异常，会把真正的错误盖掉。
            log.warn("删除媒体文件失败 key={} path={}", key, target, e);
        }
    }

    /**
     * key → 绝对路径，并断言结果仍在根目录之内。
     *
     * <p>判据是 {@code startsWith(root)} 而<b>不是</b>「key 里不含 {@code ..}」：
     * 后者是黑名单，而路径的等价形式远不止 {@code ..}
     * （{@code ./}、多余斜杠、Windows 的反斜杠与 UNC 前缀）。
     * 先规范化再比前缀，等价形式会被同一套规则消掉。
     */
    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "object key 不能为空");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new TmException(ErrorCode.INVALID_PARAMETER,
                    "object key 越出了存储根目录 key=" + key);
        }
        return path;
    }

    private void quietlyDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理临时文件失败 path={}", path, e);
        } catch (UncheckedIOException e) {
            log.warn("清理临时文件失败 path={}", path, e);
        }
    }
}
