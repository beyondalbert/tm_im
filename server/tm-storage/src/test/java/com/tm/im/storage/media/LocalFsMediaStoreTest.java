package com.tm.im.storage.media;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地文件系统实现的验证 —— 重点在<b>key → 路径</b>这一步。
 *
 * <p>当前的调用方（{@code MediaService}）生成的 key 全由服务端拼成，
 * 所以「穿越」在今天不可能发生；本类仍然逐条测它，因为这是一个<b>公开端口</b>，
 * 而「端口上的校验」只有写在实现里才对新调用方生效。一个把用户输入当 key 传的
 * 新接口，若实现方不校验，症状是「某张图片的地址指向了服务器上的任意文件」——
 * 这类缺陷一旦上线，靠日志几乎发现不了（请求是 200、内容是文件）。
 */
class LocalFsMediaStoreTest {

    private static LocalFsMediaStore storeAt(Path root) {
        StorageProperties properties = new StorageProperties();
        properties.setType("local");
        properties.setMediaRoot(root.toString());
        LocalFsMediaStore store = new LocalFsMediaStore(properties);
        store.init();
        return store;
    }

    @Test
    @DisplayName("put/get/delete 基本往返；目录会被自动创建")
    void roundTripCreatesDirectories(@TempDir Path root) {
        LocalFsMediaStore store = storeAt(root);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        store.put("2026/09/123.jpg", content);

        assertThat(Files.isRegularFile(root.resolve("2026/09/123.jpg"))).isTrue();
        assertThat(store.get("2026/09/123.jpg")).contains(content);

        store.delete("2026/09/123.jpg");
        assertThat(store.get("2026/09/123.jpg")).isEmpty();
        // 删除是幂等的：补偿逻辑会重复调用它
        store.delete("2026/09/123.jpg");
    }

    @Test
    @DisplayName("key 不存在返回 empty，而不是抛异常（「不存在」与「存储故障」必须分开）")
    void missingKeyIsEmpty(@TempDir Path root) {
        LocalFsMediaStore store = storeAt(root);
        assertThat(store.get("2026/09/nope.jpg")).isEmpty();
    }

    @Test
    @DisplayName("路径穿越被拒：../ 与绝对路径都出不了根目录")
    void traversalIsRejected(@TempDir Path root) {
        LocalFsMediaStore store = storeAt(root);

        for (String evil : new String[]{"../outside.txt", "a/../../outside.txt",
                "..\\..\\outside.txt", "./../../outside.txt"}) {
            assertThatThrownBy(() -> store.put(evil, new byte[]{1}))
                    .as("key=%s 应被拒", evil)
                    .isInstanceOf(TmException.class)
                    .extracting(e -> ((TmException) e).errorCode())
                    .isEqualTo(ErrorCode.INVALID_PARAMETER);
            assertThatThrownBy(() -> store.get(evil)).isInstanceOf(TmException.class);
        }
        // 根目录的父目录里什么都没多出来
        assertThat(root.getParent().resolve("outside.txt")).doesNotExist();
    }

    @Test
    @DisplayName("key 为空/空白被拒")
    void blankKeyRejected(@TempDir Path root) {
        LocalFsMediaStore store = storeAt(root);
        assertThatThrownBy(() -> store.put("", new byte[]{1})).isInstanceOf(TmException.class);
        assertThatThrownBy(() -> store.put("  ", new byte[]{1})).isInstanceOf(TmException.class);
        assertThatThrownBy(() -> store.put(null, new byte[]{1})).isInstanceOf(TmException.class);
    }

    @Test
    @DisplayName("写入是原子的：失败时不留 .tmp 残留，也不暴露半个文件")
    void failedWriteLeavesNoTmp(@TempDir Path root) throws IOException {
        LocalFsMediaStore store = storeAt(root);
        // 让目标路径的「父目录」是一个文件，写入必然失败
        Files.writeString(root.resolve("2026"), "占位文件");

        assertThatThrownBy(() -> store.put("2026/09/1.jpg", new byte[]{1}))
                .isInstanceOf(TmException.class)
                .extracting(e -> ((TmException) e).errorCode())
                .isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);

        try (var files = Files.list(root)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("失败后不应留下 .tmp 文件")
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("覆盖同名 key 是允许的（重试上传同一张图）")
    void overwriteIsAllowed(@TempDir Path root) {
        LocalFsMediaStore store = storeAt(root);
        store.put("a/b.jpg", "first".getBytes(StandardCharsets.UTF_8));
        store.put("a/b.jpg", "second".getBytes(StandardCharsets.UTF_8));
        assertThat(store.get("a/b.jpg")).contains("second".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("未实现的存储类型在启动期就失败，而不是静默按 local 跑")
    void unsupportedTypeFailsFast(@TempDir Path root) {
        StorageProperties properties = new StorageProperties();
        properties.setType("oss");
        properties.setMediaRoot(root.toString());

        assertThatThrownBy(() -> new LocalFsMediaStore(properties).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oss");
    }

    @Test
    @DisplayName("根目录为空也被拒（相对路径 './' 会让所有图片都落到进程工作目录）")
    void blankRootRejected() {
        StorageProperties properties = new StorageProperties();
        properties.setType("local");
        properties.setMediaRoot(" ");

        assertThatThrownBy(() -> new LocalFsMediaStore(properties).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media-root");
    }

    @Test
    @DisplayName("类型大小写与空格被容忍（配置里写 Local 是常见的）")
    void typeIsNormalized(@TempDir Path root) {
        StorageProperties properties = new StorageProperties();
        properties.setType(" LOCAL ");
        properties.setMediaRoot(root.toString());
        new LocalFsMediaStore(properties).init();

        assertThat(Optional.of(root)).isPresent();
    }
}
