package com.tm.im.core.media;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.domain.entity.Media;
import com.tm.im.domain.media.MediaStore;
import com.tm.im.domain.repository.MediaRepository;
import com.tm.im.storage.media.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Optional;

/**
 * 图片上传与下载 —— 03-rest-api.md §5 的实现（「先传后引」）。
 *
 * <p><b>核心约定：大对象不走消息通道</b>。上传拿 {@code media_id}，
 * 消息体只引用这个 id（见 §5.3 与 {@code MessageService.validateImage}）。
 * 好处是消息帧小、长连接不被大字节阻塞、将来可整层换成 CDN。
 *
 * <pre>
 *   上传：校验大小 → 嗅探格式 → 读尺寸 → 缩放缩略图
 *         → 写字节（先）→ 写元数据（后）→ 拼 URL
 *   下载：查元数据（40408 若没有）→ 取字节（40408 若盘上没有）
 * </pre>
 *
 * <h2>格式以<b>内容</b>为准，不以请求头为准</h2>
 *
 * <p>客户端声明的 {@code Content-Type} 与文件名后缀<b>完全不参与判断</b>。
 * 这不是谨慎过度，而是唯一正确的做法：若按声明值决定响应头，
 * 一个把 {@code <script>} 存成 {@code image/png} 的上传就会让下载接口
 * 以 {@code image/png} 返回一段 HTML——浏览器不会执行它（因为类型是图片），
 * 但只要有人把响应头改成 {@code text/html}（或客户端按后缀猜类型），
 * 同一个文件就变成了存储型 XSS。把「服务器相信的内容类型」与
 * 「客户端随便说的字符串」分开，是这条链路上唯一的防线；
 * 因此本类只认 magic bytes，且下载时额外带 {@code X-Content-Type-Options: nosniff}。
 *
 * <h2>webp 的半支持是明说的</h2>
 *
 * <p>JDK 自带 ImageIO 没有 WebP 解码器（{@link #sniff} 能认出它，但读不出像素）。
 * 于是 webp 图片：{@code width}/{@code height} 返回 {@code null}，
 * 不生成缩略图，{@code thumb_url} 与 {@code url} 相同（{@code ?thumb=1} 回原图）。
 * 另一条路是直接拒收 webp，但它在 §5.1 的白名单里——拒收一个文档允许的格式
 * 会让实现与文档对不上，而「宽高为 null、缩略图就是原图」是一个客户端能处理的降级
 * （客户端本来就要处理缩略图 404 的情况，见 03-rest-api.md §5.2）。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /**
     * 允许的图片格式 —— 03-rest-api.md §5.1 的白名单，也是 {@code 40013} 的判据。
     *
     * <p>刻意<b>不做成配置项</b>：这是一条<b>安全</b>白名单（决定哪些字节能被
     * 当作图片存下来并由本服务回放给所有人），它的每一次放宽都应当伴随一次
     * 代码评审，而不是一次配置修改。产品侧的额度（大小、频率）才是配置项。
     */
    enum Format {
        JPEG("image/jpeg", "jpg", true),
        PNG("image/png", "png", true),
        GIF("image/gif", "gif", true),

        /** 见类注释：能识别、能存取，但 JDK 解不了码，所以没有尺寸与缩略图。 */
        WEBP("image/webp", "webp", false);

        private final String mime;
        private final String extension;

        /** 本 JDK 能否解码（决定要不要读尺寸、生成缩略图）。 */
        private final boolean decodable;

        Format(String mime, String extension, boolean decodable) {
            this.mime = mime;
            this.extension = extension;
            this.decodable = decodable;
        }

        String mime() {
            return mime;
        }

        String extension() {
            return extension;
        }

        boolean decodable() {
            return decodable;
        }
    }

    /**
     * 像素总数上限（4000 万，约 8000×5000）—— 用于挡「解压炸弹」。
     *
     * <p>一个几十 KB 的 PNG 可以声明 5 万×5 万的画布，{@code ImageIO.read} 会真的
     * 去分配 5万×5万×4 字节 ≈ 10GB：那张图过不了 10MB 的体积校验，
     * 却足以让一个 JVM 直接 OOM。所以「体积」这一道拦不住它，
     * 必须在解码<b>之前</b>用图片头里的宽高做一次判断
     * （{@link #readSize} 只读头，不分配像素缓冲）。
     *
     * <p>常量而不做成配置：它是内存安全的护栏，不是产品配额。
     * 产品配额是 {@code tm.storage.max-size-bytes}。
     */
    private static final long MAX_PIXELS = 40_000_000L;

    /** 目录分桶用（{@code yyyy/MM}），见 {@code LocalFsMediaStore} 对目录规模的理由。 */
    private static final DateTimeFormatter KEY_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    /** 缩略图固定用 JPEG：体积最小、所有客户端都能画，且缩略图不需要透明通道。 */
    private static final String THUMB_MIME = "image/jpeg";

    /**
     * 缩略图 key 的推导规则：原 key 加后缀。
     *
     * <p>不落库（{@code media} 表没有 thumb_key 列）也不加列：它是<b>纯函数</b>，
     * 而纯函数不该占一列——占列就多一个可能与原 key 不同步的字段，
     * 而不同步的表现是「缩略图永远 404、原图正常」，看起来像缓存问题。
     */
    private static final String THUMB_SUFFIX = ".thumb.jpg";

    private final MediaRepository media;
    private final MediaStore store;
    private final IdGenerator idGenerator;
    private final StorageProperties properties;
    private final ZoneId databaseZone;

    public MediaService(MediaRepository media,
                        MediaStore store,
                        IdGenerator idGenerator,
                        StorageProperties properties,
                        ZoneId databaseZone) {
        this.media = media;
        this.store = store;
        this.idGenerator = idGenerator;
        this.properties = properties;
        this.databaseZone = databaseZone;
    }

    // ================================================================== 上传

    /**
     * 上传结果。
     *
     * @param url      {@code media-public-base}/{@code id}
     * @param thumbUrl 缩略图地址；webp 等无法解码的格式与 {@code url} 相同（见类注释）
     */
    public record Uploaded(Media media, String url, String thumbUrl) {
    }

    /**
     * 上传一张图片（§5.1）。
     *
     * @param bytes 原始字节；由调用方从 multipart 里取出
     * @throws TmException 40014（超限）、40013（格式不支持或内容损坏）、50003（存储不可用）
     */
    public Uploaded upload(long ownerId, String filename, byte[] bytes) {
        if (ownerId <= 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "owner_id 必须为正整数: " + ownerId);
        }
        if (bytes == null || bytes.length == 0) {
            throw new TmException(ErrorCode.MISSING_PARAMETER, "上传内容为空");
        }
        if (bytes.length > properties.getMaxSizeBytes()) {
            throw new TmException(ErrorCode.IMAGE_TOO_LARGE,
                    "大小 " + bytes.length + " 字节超过上限 " + properties.getMaxSizeBytes() + " 字节");
        }

        Format format = sniff(bytes)
                .orElseThrow(() -> new TmException(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                        "只支持 jpeg/png/gif/webp（按内容判断，不看文件名与 Content-Type）："
                                + safeName(filename)));

        long mediaId = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now(databaseZone);
        String objectKey = KEY_MONTH.format(now) + "/" + mediaId + "." + format.extension();

        Size size = format.decodable() ? readSize(bytes, filename) : Size.UNKNOWN;
        // 缩略图先算出来再落盘：算的过程会失败（损坏的图、解压炸弹），
        // 那时一个字节都还没写下去，没有需要回收的东西。
        byte[] thumb = format.decodable() ? thumbnail(bytes, filename) : null;

        // 顺序：字节先、元数据后（见 MediaRepository#insert 的注释）。
        store.put(objectKey, bytes);
        try {
            if (thumb != null) {
                store.put(objectKey + THUMB_SUFFIX, thumb);
            }
        } catch (RuntimeException e) {
            // 缩略图写失败就不该继续：整张图会处于「原图可读、缩略图 404」的半状态，
            // 而客户端首屏一律先请求 thumb_url。重试一次上传要便宜得多。
            store.delete(objectKey);
            throw e;
        }

        Media row = new Media();
        row.setId(mediaId);
        row.setOwnerId(ownerId);
        row.setObjectKey(objectKey);
        row.setMime(format.mime());
        row.setWidth(size.width());
        row.setHeight(size.height());
        row.setSizeBytes((long) bytes.length);
        row.setCreatedAt(now);
        try {
            media.insert(row);
        } catch (RuntimeException e) {
            // 落库失败 → 刚才写进去的字节就是孤儿，且没有任何索引能指向它。
            // 这里 best-effort 回收：删失败只记日志（见 MediaStore#delete 的语义），
            // 因为「补偿失败」不该盖掉真正的错误。
            store.delete(objectKey);
            store.delete(objectKey + THUMB_SUFFIX);
            throw e;
        }

        log.debug("图片上传完成 mediaId={} owner={} mime={} bytes={}",
                mediaId, ownerId, format.mime(), bytes.length);
        return new Uploaded(row, url(mediaId), thumbUrl(mediaId, thumb != null));
    }

    // ================================================================== 下载

    /** 下载结果：字节 + 响应头要用的 Content-Type。 */
    public record Content(byte[] bytes, String mime) {
    }

    /**
     * 读取图片字节（§5.2）。
     *
     * <p><b>权限模型：登录即读，id 就是凭证</b>。这里刻意不做「只允许上传者读」的
     * 归属校验——那张图会被发进会话，收件人必须能打开它，而「谁收到过这张图」需要
     * 反查 16 张消息分片表（{@code content} 是 JSON，且不带 {@code conv_id} 的查询
     * 正是 §8.7 明令禁止的广播）。{@code media_id} 是雪花号，不可枚举；
     * 拿到它的人本来就已经拿到了引用它的那条消息。这条取舍与「带签名的 CDN URL」
     * 是同一类做法（把 id 当 capability），只是省掉了签名与过期时间——
     * 相应地它也更弱：id 泄露即长期可读，所以下载响应的缓存策略写成不可变
     * （见 {@code MediaController}），且 id 绝不进日志明细之外的地方。
     *
     * @param thumb true = 取缩略图；无法解码的格式回原图（见类注释）
     * @throws TmException 40008（元数据不存在 / 盘上文件不存在）
     */
    public Content load(long mediaId, boolean thumb) {
        Media row = require(mediaId);
        if (!thumb) {
            return new Content(readBlob(row.getObjectKey(), mediaId), row.getMime());
        }
        Optional<byte[]> smaller = store.get(row.getObjectKey() + THUMB_SUFFIX);
        if (smaller.isPresent()) {
            return new Content(smaller.get(), THUMB_MIME);
        }
        // 没有缩略图 = 上传时没生成（webp）。回原图而不是 404：
        // 客户端把 thumb_url 当主要地址用（首屏一律先请求它），而 404 会让
        // 列表里出现一片空框，尽管原图就在那儿。
        log.debug("该媒体没有缩略图，回原图 mediaId={} mime={}", mediaId, row.getMime());
        return new Content(readBlob(row.getObjectKey(), mediaId), row.getMime());
    }

    /** 元数据（供消息内容校验与运维排查用）。 */
    public Media require(long mediaId) {
        if (mediaId <= 0) {
            throw new TmException(ErrorCode.INVALID_PARAMETER, "media_id 必须为正整数: " + mediaId);
        }
        return media.findById(mediaId)
                .orElseThrow(() -> new TmException(ErrorCode.MEDIA_NOT_FOUND, "mediaId=" + mediaId));
    }

    /** 对外 URL。缩略图与 url 同源，只差一个查询参数（§5.1 的示例）。 */
    public String url(long mediaId) {
        return base() + "/" + mediaId;
    }

    public String thumbUrl(long mediaId, boolean hasThumb) {
        return hasThumb ? url(mediaId) + "?thumb=1" : url(mediaId);
    }

    // ================================================================== 内部

    private String base() {
        String configured = properties.getMediaPublicBase();
        String value = configured == null || configured.isBlank()
                ? "http://localhost:8080/v1/media" : configured.strip();
        // 去掉结尾斜杠：模板里那一行是运维手写的，多一个 '/' 会拼出 //v1/media/123，
        // 而那个 URL 大多数客户端能容忍、少数签名校验会挂——不值得让它成为变量。
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private byte[] readBlob(String objectKey, long mediaId) {
        return store.get(objectKey).orElseThrow(() -> {
            // 「库里有、盘上没有」是运维事件（磁盘被清理、迁移漏文件），
            // 对客户端只能回 40008——它该做的动作与「这个 id 不存在」完全一样。
            log.error("媒体元数据存在但字节缺失 mediaId={} key={} —— 可能是磁盘被清理或迁移不完整",
                    mediaId, objectKey);
            return new TmException(ErrorCode.MEDIA_NOT_FOUND, "mediaId=" + mediaId);
        });
    }

    private record Size(Integer width, Integer height) {
        static final Size UNKNOWN = new Size(null, null);
    }

    /**
     * 按 magic bytes 判断格式。
     *
     * <p>四种格式的判据都取「尽可能短但不会误判」的前缀：
     * <pre>
     *   JPEG  FF D8 FF
     *   PNG   89 50 4E 47 0D 0A 1A 0A     （8 字节完整签名，防的是有人拿 4 字节前缀骗过）
     *   GIF   "GIF87a" / "GIF89a"
     *   WEBP  "RIFF" ?? ?? ?? ?? "WEBP"   （中间 4 字节是文件长度，必须跳过）
     * </pre>
     */
    static Optional<Format> sniff(byte[] b) {
        if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(Format.JPEG);
        }
        if (startsWith(b, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(Format.PNG);
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return Optional.of(Format.GIF);
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of(Format.WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b == null || b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 只读图片头拿尺寸，<b>不解码像素</b>（见 {@link #MAX_PIXELS} 的炸弹说明）。
     *
     * <p>{@code ImageIO.getImageReaders} 找不到 reader 说明这个「magic bytes 对了」的
     * 文件其实解不开（截断、损坏、或被伪造了前缀）。此时回 40013 而不是存下来：
     * 一张服务端无法解码的图，客户端多半也解不开，而我们已经把它当成合法图片
     * 写进了库、并回了一个 200——那个错误要等到很久以后才会以「图片全裂」的形式暴露。
     */
    private Size readSize(byte[] bytes, String filename) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                throw unsupported(filename, "无法建立图片流");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw unsupported(filename, "无法解码（文件损坏或格式与内容不符）");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_PIXELS) {
                    throw new TmException(ErrorCode.IMAGE_TOO_LARGE,
                            "像素 " + width + "x" + height + " 超过上限 " + MAX_PIXELS
                                    + "（体积未超限也可能是解压炸弹）");
                }
                return new Size(width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw unsupported(filename, "读图片头失败: " + e.getMessage());
        }
    }

    /**
     * 生成缩略图（长边缩到 {@code max-thumb-side}，等比，不放大）。
     *
     * <p>缩放用「一次 getScaledInstance 风格的双线性插值」的等价写法
     * （{@code drawImage} + {@code RenderingHints}）：{@code getScaledInstance}
     * 返回的是延迟计算的 Image，在 JPEG 编码时可能触发极慢的路径。
     *
     * <p>目标类型固定 {@code TYPE_INT_RGB}：JPEG 不支持 alpha 通道，
     * 若把带透明的 PNG（{@code TYPE_INT_ARGB}）直接交给 JPEG 编码器，
     * ImageIO 会抛 {@code IIOException: Bogus input colorspace}——
     * 一个只在「用户传了透明 PNG」时才出现的 500。
     */
    private byte[] thumbnail(byte[] bytes, String filename) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw unsupported(filename, "生成缩略图时解码失败: " + e.getMessage());
        }
        if (source == null) {
            throw unsupported(filename, "生成缩略图时无法解码");
        }

        int maxSide = Math.max(16, properties.getMaxThumbSide());
        int longSide = Math.max(source.getWidth(), source.getHeight());
        double scale = longSide <= maxSide ? 1.0 : (double) maxSide / longSide;
        int targetW = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 透明像素在 RGB 画布上会变成黑，显式铺一层白底更符合直觉
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
            g.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, bytes.length / 8));
        try {
            if (!ImageIO.write(target, "jpg", out)) {
                throw unsupported(filename, "当前 JVM 没有 JPEG 编码器");
            }
        } catch (IOException e) {
            throw unsupported(filename, "缩略图编码失败: " + e.getMessage());
        }
        return out.toByteArray();
    }

    private static TmException unsupported(String filename, String why) {
        return new TmException(ErrorCode.UNSUPPORTED_IMAGE_TYPE, why + "（" + safeName(filename) + "）");
    }

    /**
     * 文件名只用于错误信息，且<b>截断 + 去掉路径分隔符</b>：它是不可信输入，
     * 会出现在日志与（经 detail 隐藏后的）错误上下文里。回显一个带
     * {@code ../} 或换行的文件名，本身就是一个日志注入与误导的来源。
     */
    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "<未提供文件名>";
        }
        String cleaned = filename.replace('/', '_').replace('\\', '_');
        String noControl = cleaned.replaceAll("[\\p{Cntrl}]", "?");
        return noControl.length() <= 64 ? noControl : noControl.substring(0, 64) + "...";
    }
}
