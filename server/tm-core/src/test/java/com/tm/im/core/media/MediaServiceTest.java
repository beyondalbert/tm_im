package com.tm.im.core.media;

import com.tm.im.common.error.ErrorCode;
import com.tm.im.common.error.TmException;
import com.tm.im.common.id.IdGenerator;
import com.tm.im.domain.entity.Media;
import com.tm.im.storage.media.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图片上传/下载的规则验证（03-rest-api.md §5）。
 *
 * <p>这个类里绝大多数用例针对的都是「改坏了照样能跑」那一类规则：
 * 按文件名而不是内容判断格式、缩略图 key 与推导规则不一致、落库失败后不回收字节、
 * 解压炸弹不拦。它们的共同点是<b>功能测试全绿</b>——上传能成功、下载能打开，
 * 只有把断言写在具体的字节/键/顺序上才会失败。
 *
 * <p>所有图片夹具都在测试里<b>现场生成</b>（{@code ImageIO.write}），
 * 而不是往仓库里放二进制文件：二进制夹具无法 review（谁也没法在 diff 里看出
 * 一张 PNG 改了什么），且一旦需要换格式就得重新造。
 */
class MediaServiceTest {

    private static final long OWNER = 1001L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private InMemoryMediaStore store;
    private InMemoryMediaRepository repository;
    private StorageProperties properties;
    private MediaService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryMediaStore();
        repository = new InMemoryMediaRepository();
        properties = new StorageProperties();
        properties.setMaxSizeBytes(10 * 1024 * 1024);
        properties.setMaxThumbSide(64);
        properties.setMediaPublicBase("http://media.test/v1/media");
        service = new MediaService(repository, store, new SequentialIds(), properties, ZONE);
    }

    // ================================================================== 上传校验

    @Test
    @DisplayName("空内容 → 40001，且一个字节都不写")
    void emptyUploadRejected() {
        assertThat(codeOf(() -> service.upload(OWNER, "a.png", new byte[0])))
                .isEqualTo(ErrorCode.MISSING_PARAMETER);
        assertThat(store.size()).isZero();
        assertThat(repository.size()).isZero();
    }

    @Test
    @DisplayName("超过 max-size-bytes → 40014，且发生在解析格式之前")
    void oversizeUploadRejected() {
        properties.setMaxSizeBytes(1024);
        byte[] big = png(200, 200);   // 远大于 1KB 的 PNG
        assertThat(big.length).isGreaterThan(1024);

        assertThat(codeOf(() -> service.upload(OWNER, "a.png", big)))
                .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("非图片内容 → 40013（按内容判断，不看文件名与 Content-Type）")
    void unknownFormatRejected() {
        byte[] text = "这不是图片".getBytes(StandardCharsets.UTF_8);
        assertThat(codeOf(() -> service.upload(OWNER, "note.txt", text)))
                .isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        // 文件名叫 photo.jpg 也一样拒：名字是不可信输入
        assertThat(codeOf(() -> service.upload(OWNER, "photo.jpg", text)))
                .isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
    }

    @Test
    @DisplayName("magic bytes 对但内容损坏 → 40013（不是 200 + 一张解不开的图）")
    void corruptImageRejected() {
        byte[] claimedPng = new byte[64];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
                0, claimedPng, 0, 8);
        assertThat(codeOf(() -> service.upload(OWNER, "broken.png", claimedPng)))
                .isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("解压炸弹（体积很小、声明像素极大）→ 40014，且在分配像素之前拦住")
    void decompressionBombRejected() {
        byte[] bomb = pngWithDeclaredSize(40000, 40000);
        // 前提：它确实远小于体积上限，所以体积校验拦不住它
        assertThat(bomb.length).isLessThan(1024);

        assertThat(codeOf(() -> service.upload(OWNER, "bomb.png", bomb)))
                .isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
        assertThat(store.size()).as("被拦下时不应留下任何字节").isZero();
    }

    // ================================================================== 上传成功路径

    @Test
    @DisplayName("格式取自内容：文件名 photo.png 装的是 jpeg，mime 与扩展名都按 jpeg")
    void formatsComeFromContentNotFilename() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "photo.png", jpeg(10, 10));

        assertThat(uploaded.media().getMime()).isEqualTo("image/jpeg");
        assertThat(uploaded.media().getObjectKey()).endsWith(".jpg");
    }

    @Test
    @DisplayName("字节先落盘、元数据后落库，且顺序与缩略图一致")
    void bytesAreStoredBeforeMetadata() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", png(20, 20));
        String key = uploaded.media().getObjectKey();

        assertThat(store.writeOrder())
                .containsExactly("put:" + key, "put:" + key + ".thumb.jpg");
        assertThat(store.has(key)).isTrue();
    }

    @Test
    @DisplayName("元数据字段：id/owner/mime/宽高/字节数都来自服务端自己的观察")
    void metadataIsBuiltFromObservedContent() {
        byte[] bytes = png(37, 19);
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", bytes);
        Media media = uploaded.media();

        assertThat(media.getOwnerId()).isEqualTo(OWNER);
        assertThat(media.getWidth()).isEqualTo(37);
        assertThat(media.getHeight()).isEqualTo(19);
        assertThat(media.getSizeBytes()).isEqualTo((long) bytes.length);
        assertThat(media.getCreatedAt()).isNotNull();
        assertThat(repository.findById(media.getId())).contains(media);
    }

    @Test
    @DisplayName("宽高写反也一样工作：横图与竖图都按实际像素记")
    void portraitImageDimensionsPreserved() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", png(19, 37));
        assertThat(uploaded.media().getWidth()).isEqualTo(19);
        assertThat(uploaded.media().getHeight()).isEqualTo(37);
    }

    @Test
    @DisplayName("缩略图长边等于 max-thumb-side，且原图不被改动")
    void thumbnailIsScaledToConfiguredLongSide() throws IOException {
        byte[] original = png(200, 100);
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", original);
        String key = uploaded.media().getObjectKey();

        assertThat(store.bytes(key)).isEqualTo(original);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(store.bytes(key + ".thumb.jpg")));
        assertThat(thumb).isNotNull();
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(64);
        // 等比：200x100 → 64x32
        assertThat(thumb.getWidth()).isEqualTo(64);
        assertThat(thumb.getHeight()).isEqualTo(32);
    }

    @Test
    @DisplayName("小图不放大：长边已经小于上限时缩略图尺寸不变")
    void thumbnailDoesNotUpscale() throws IOException {
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", png(32, 16));
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(
                store.bytes(uploaded.media().getObjectKey() + ".thumb.jpg")));
        assertThat(thumb.getWidth()).isEqualTo(32);
        assertThat(thumb.getHeight()).isEqualTo(16);
    }

    @Test
    @DisplayName("带透明通道的 PNG 也能出缩略图（目标画布是 RGB，JPEG 不支持 alpha）")
    void transparentPngThumbnailWorks() throws IOException {
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(255, 0, 0, 128));
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        MediaService.Uploaded uploaded = service.upload(OWNER, "alpha.png", out.toByteArray());
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(
                store.bytes(uploaded.media().getObjectKey() + ".thumb.jpg")));
        assertThat(thumb).as("透明 PNG 的缩略图必须真的生成出来").isNotNull();
    }

    @Test
    @DisplayName("webp：能存取，但没有尺寸与缩略图，thumb_url 与 url 相同")
    void webpIsStoredWithoutThumbnail() {
        byte[] webp = webp();
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.webp", webp);

        assertThat(uploaded.media().getMime()).isEqualTo("image/webp");
        assertThat(uploaded.media().getWidth()).isNull();
        assertThat(uploaded.media().getHeight()).isNull();
        assertThat(uploaded.thumbUrl()).isEqualTo(uploaded.url());
        assertThat(store.has(uploaded.media().getObjectKey() + ".thumb.jpg")).isFalse();
    }

    @Test
    @DisplayName("落库失败时回收刚写下去的字节（不留孤儿文件）")
    void orphanBytesAreRemovedWhenInsertFails() {
        repository.failNextInsert();

        assertThatThrownBy(() -> service.upload(OWNER, "a.png", png(10, 10)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        assertThat(store.size()).as("盘上不应留下任何无人引用的文件").isZero();
        assertThat(repository.size()).isZero();
    }

    @Test
    @DisplayName("object key 里不含用户输入：id 由服务端生成，扩展名取自内容")
    void objectKeyIsServerGenerated() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "../../etc/passwd", png(4, 4));
        assertThat(uploaded.media().getObjectKey())
                .matches("\\d{4}/\\d{2}/\\d+\\.png")
                .doesNotContain("..")
                .doesNotContain("passwd");
    }

    @Test
    @DisplayName("owner 非法直接拒绝，不产生任何副作用")
    void invalidOwnerRejected() {
        assertThat(codeOf(() -> service.upload(0, "a.png", png(4, 4))))
                .isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(store.size()).isZero();
    }

    // ================================================================== 下载

    @Test
    @DisplayName("原图与缩略图各自回自己的 mime")
    void downloadReturnsOriginalAndThumbnail() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", png(200, 100));
        long id = uploaded.media().getId();

        MediaService.Content original = service.load(id, false);
        assertThat(original.mime()).isEqualTo("image/png");
        assertThat(original.bytes()).isEqualTo(store.bytes(uploaded.media().getObjectKey()));

        MediaService.Content thumb = service.load(id, true);
        assertThat(thumb.mime()).isEqualTo("image/jpeg");
        assertThat(thumb.bytes()).isNotEqualTo(original.bytes());
    }

    @Test
    @DisplayName("没有缩略图时 ?thumb=1 回原图而不是 404（webp 场景）")
    void thumbFallsBackToOriginalWhenAbsent() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.webp", webp());
        MediaService.Content thumb = service.load(uploaded.media().getId(), true);

        assertThat(thumb.mime()).isEqualTo("image/webp");
        assertThat(thumb.bytes()).isEqualTo(webp());
    }

    @Test
    @DisplayName("id 不存在 → 40008；id 非法 → 40002")
    void missingMediaReported() {
        assertThat(codeOf(() -> service.load(123456789L, false))).isEqualTo(ErrorCode.MEDIA_NOT_FOUND);
        assertThat(codeOf(() -> service.load(0L, false))).isEqualTo(ErrorCode.INVALID_PARAMETER);
        assertThat(codeOf(() -> service.load(-1L, true))).isEqualTo(ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("库里有、盘上没这个文件 → 40008（且是 MEDIA_NOT_FOUND，不是 500）")
    void missingBlobReportedAsNotFound() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", png(10, 10));
        String key = uploaded.media().getObjectKey();
        store.delete(key);
        store.delete(key + ".thumb.jpg");

        assertThat(codeOf(() -> service.load(uploaded.media().getId(), false)))
                .isEqualTo(ErrorCode.MEDIA_NOT_FOUND);
        assertThat(codeOf(() -> service.load(uploaded.media().getId(), true)))
                .isEqualTo(ErrorCode.MEDIA_NOT_FOUND);
    }

    // ================================================================== URL

    @Test
    @DisplayName("url/thumb_url 依据 media-public-base 拼接，结尾多余的斜杠被去掉")
    void urlsAreBuiltFromConfiguredBase() {
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", png(10, 10));
        long id = uploaded.media().getId();
        assertThat(uploaded.url()).isEqualTo("http://media.test/v1/media/" + id);
        assertThat(uploaded.thumbUrl()).isEqualTo("http://media.test/v1/media/" + id + "?thumb=1");

        properties.setMediaPublicBase("https://cdn.example.com/tm/media/");
        assertThat(service.url(id)).isEqualTo("https://cdn.example.com/tm/media/" + id);
    }

    @Test
    @DisplayName("media-public-base 没配时退化为本机地址，而不是拼出 \"null/123\"")
    void baseFallsBackWhenUnset() {
        properties.setMediaPublicBase(null);
        MediaService.Uploaded uploaded = service.upload(OWNER, "a.png", png(10, 10));
        assertThat(uploaded.url()).isEqualTo("http://localhost:8080/v1/media/" + uploaded.media().getId());
    }

    // ================================================================== 夹具

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("期望抛 TmException，实际成功");
        } catch (TmException e) {
            return e.errorCode();
        }
    }

    private static byte[] png(int width, int height) {
        return encode(image(width, height), "png");
    }

    private static byte[] jpeg(int width, int height) {
        return encode(image(width, height), "jpg");
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        // 画点东西：纯色图会被 PNG 压到几十字节，而「缩略图真的缩了」需要非平凡内容
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.drawLine(0, 0, width - 1, height - 1);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 最小 webp 探针：只有 RIFF/WEBP 头与四个占位字节。
     *
     * <p>它不是一张真的 webp，<b>而这正是被测行为的前提</b>：
     * 「当前 JVM 解不了 webp」这件事等价于「只要不尝试解码，它就照样能存取」。
     * 若哪天给 JVM 装上 webp 解码器，这个夹具会以 40013 失败——
     * 那正是应该发生的事（那时 webp 应当走可解码分支，实现与文档都要跟着改）。
     */
    private static byte[] webp() {
        byte[] bytes = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        bytes[4] = 4;   // 文件长度占位
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }

    /**
     * 造一个「声明 4 万×4 万、实际只有几十字节」的 PNG。
     *
     * <p>做法是在一张真 PNG 上改写 IHDR 的宽高并重算它的 CRC32——PNG 的
     * 解码器在 {@code getWidth()} 阶段只读 IHDR，不会去校验 IDAT 是否够大，
     * 因此这个文件足以触发「按声明尺寸分配像素」的那条路径。
     * 若改成「生成一张真的 4 万×4 万的图」，测试本身就要先吃掉 6GB 内存。
     */
    private static byte[] pngWithDeclaredSize(int width, int height) {
        byte[] bytes = png(1, 1);
        writeInt(bytes, 16, width);    // IHDR data 起始 = 签名(8) + 长度(4) + 类型(4)
        writeInt(bytes, 20, height);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);     // 类型(4) + 数据(13)
        writeInt(bytes, 29, (int) crc.getValue());
        return bytes;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    /** 递增 id 替身：让断言能对着具体 id 说话（真实实现与时间相关，测不了相等）。 */
    private static final class SequentialIds implements IdGenerator {

        private long next = 660_000_000_000_000_000L;

        @Override
        public long nextId() {
            return ++next;
        }

        @Override
        public int nodeId() {
            return 1;
        }
    }
}
