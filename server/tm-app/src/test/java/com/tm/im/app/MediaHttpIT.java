package com.tm.im.app;

import com.tm.im.domain.enums.SecretType;
import com.tm.im.domain.repository.ActorRepository;
import com.tm.im.domain.repository.ActorSecretRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片上传/下载的端到端验证（03-rest-api.md §5）——<b>真实 multipart + 真实文件系统</b>。
 *
 * <p><b>为什么必须有它</b>（单测覆盖不到的四件事）：
 * <ol>
 *   <li><b>multipart 真的能被解析成 {@code MultipartFile}</b>：控制器参数名是 {@code file}，
 *       而这个名字是文档里 {@code curl -F "file=@..."} 的一部分——拼错的表现是一个永远 40001 的接口；</li>
 *   <li><b>下载响应的头</b>：{@code Content-Type}（来自内容嗅探）、
 *       {@code X-Content-Type-Options: nosniff}、{@code Cache-Control: immutable}。
 *       这三个都是「配错了照样 200」的东西，只有真的看一眼响应头才知道；</li>
 *   <li><b>两处大小限制的接缝</b>：容器（{@code spring.servlet.multipart.max-file-size}）
 *       与应用（{@code tm.storage.max-size-bytes}）。超过容器上限的请求根本到不了控制器，
 *       默认会被兜底处理器翻成 50000——而客户端对 50000 的动作是「退避重试」，
 *       重试一个超限的文件永远失败。这里把两个上限都调小，两条路径各测一次；</li>
 *   <li><b>字节真的落到了磁盘上</b>（{@code LocalFsMediaStore} 的目录分桶与原子改名）。</li>
 * </ol>
 *
 * <p>本类把 {@code tm.storage.media-root} 指到一个临时目录，并在结束时整棵树删掉：
 * 默认值 {@code ./data/media} 会把测试产生的图片留在工作目录里，
 * 而那些文件不会被任何清理逻辑认领（{@code clean_it_leftovers.py} 只认数据库与 Redis）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaHttpIT {

    private static final String JWT_SECRET = "it-jwt-secret-0123456789abcdef-32B";
    private static final String PASSWORD = "it-pass-12345678";
    private static final String SUFFIX = Long.toHexString(System.nanoTime() & 0xFFFFFF);
    private static final String HANDLE = "it_media_" + SUFFIX;

    /** 应用层上限（{@code tm.storage.max-size-bytes}）。 */
    private static final int MAX_APP_BYTES = 4096;

    /** 容器上限（{@code spring.servlet.multipart.max-file-size}），刻意比应用层大一点。 */
    private static final int MAX_CONTAINER_BYTES = 8192;

    /** 每次运行私有的媒体根目录（见类注释）。 */
    private static final Path MEDIA_ROOT =
            Path.of(System.getProperty("java.io.tmpdir"), "tm-it-media-" + SUFFIX);

    @LocalServerPort
    int httpPort;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ActorRepository actors;

    @Autowired
    ActorSecretRepository secrets;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private final List<Long> createdMedia = new ArrayList<>();
    private final Set<String> refreshTokens = new java.util.HashSet<>();

    private Account alice;

    private record Account(long actorId, String handle, String token) {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:shardingsphere:absolutepath:" + com.tm.im.storage.it.ItEnv.shardingConfig());
        registry.add("tm.identity.jwt-secret", () -> JWT_SECRET);
        registry.add("tm.time.zone", () -> "Asia/Shanghai");
        registry.add("spring.data.redis.host", () -> com.tm.im.storage.it.ItEnv.get("redis.host"));
        registry.add("spring.data.redis.port", () -> com.tm.im.storage.it.ItEnv.get("redis.port"));
        registry.add("spring.data.redis.password",
                () -> com.tm.im.storage.it.ItEnv.getOrEmpty("redis.password"));
        registry.add("spring.data.redis.database", () -> com.tm.im.storage.it.ItEnv.get("redis.db"));
        registry.add("tm.identity.refresh-token-ttl", () -> "5m");
        registry.add("tm.node.id", () -> "it-media-boot");
        registry.add("tm.node.ttl", () -> "5m");
        registry.add("tm.netty.port", () -> "0");

        registry.add("tm.storage.media-root", MEDIA_ROOT::toString);
        registry.add("tm.storage.max-size-bytes", () -> Integer.toString(MAX_APP_BYTES));
        // 缩略图长边调小，让「缩略图确实变小了」的断言与具体像素值绑定得上
        registry.add("tm.storage.max-thumb-side", () -> "64");
        registry.add("spring.servlet.multipart.max-file-size",
                () -> MAX_CONTAINER_BYTES + "B");
        registry.add("spring.servlet.multipart.max-request-size",
                () -> (MAX_CONTAINER_BYTES * 2) + "B");
    }

    @BeforeAll
    void setUp() {
        alice = newAccount();
    }

    @AfterAll
    void cleanUp() throws IOException {
        for (long mediaId : createdMedia) {
            jdbc.update("DELETE FROM media WHERE id = ?", mediaId);
        }
        for (String token : refreshTokens) {
            redis.delete("tm:rt:" + com.tm.im.common.crypto.RefreshTokens.hash(token));
        }
        actors.findByHandle(HANDLE).ifPresent(actor -> {
            secrets.delete(actor.getId(), SecretType.PASSWORD_HASH);
            jdbc.update("DELETE FROM actor WHERE id = ?", actor.getId());
        });
        // 整棵目录删掉：本类产生的文件没有任何清理任务认领（见类注释）
        if (Files.exists(MEDIA_ROOT)) {
            try (var paths = Files.walk(MEDIA_ROOT)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    // ================================================================ 上传 + 下载

    @Test
    @DisplayName("上传 → 响应字段 → 原图与缩略图都能取回，且响应头正确")
    void uploadThenDownload() {
        byte[] original = png(400, 200);

        Map<String, Object> uploaded = ok(upload("photo.png", original, alice.token()));
        assertSnakeCase(uploaded, "media_id", "mime", "width", "height", "size_bytes", "url", "thumb_url");
        long mediaId = number(uploaded.get("media_id"));
        createdMedia.add(mediaId);

        assertThat(uploaded.get("mime")).isEqualTo("image/png");
        assertThat(number(uploaded.get("width"))).isEqualTo(400L);
        assertThat(number(uploaded.get("height"))).isEqualTo(200L);
        assertThat(number(uploaded.get("size_bytes"))).isEqualTo((long) original.length);
        assertThat((String) uploaded.get("url")).startsWith("http").endsWith("/v1/media/" + mediaId);
        assertThat((String) uploaded.get("thumb_url")).endsWith("/v1/media/" + mediaId + "?thumb=1");

        // 下载一律用「本机随机端口 + 文档里的路径」去取，而不是用响应里那个绝对 URL：
        // 那个 URL 的基址来自 tm.storage.media-public-base（部署期知道对外域名），
        // 测试进程只知道 RANDOM_PORT。这一条是有意的取舍——本类验证的是路径与响应头，
        // 「基址拼得对不对」由 MediaServiceTest.urlsAreBuiltFromConfiguredBase 钉住。

        // ---------- 原图 ----------
        ResponseEntity<byte[]> raw = download(mediaId, false, alice.token());
        assertThat(raw.getStatusCode().value()).isEqualTo(200);
        assertThat(raw.getHeaders().getContentType().toString())
                .as("Content-Type 必须来自内容嗅探")
                .isEqualTo("image/png");
        assertThat(raw.getHeaders().getFirst("X-Content-Type-Options"))
                .as("少了它，浏览器可能把内容猜成 HTML 并执行")
                .isEqualTo("nosniff");
        assertThat(raw.getHeaders().getCacheControl()).contains("immutable");
        assertThat(raw.getBody()).isEqualTo(original);

        // ---------- 缩略图 ----------
        ResponseEntity<byte[]> thumb = download(mediaId, true, alice.token());
        assertThat(thumb.getStatusCode().value()).isEqualTo(200);
        assertThat(thumb.getHeaders().getContentType().toString())
                .as("缩略图统一是 JPEG（体积最小、所有客户端都能画）")
                .isEqualTo("image/jpeg");
        assertThat(thumb.getBody()).isNotNull();
        assertThat(thumb.getBody().length).as("缩略图必须真的比原图小").isLessThan(original.length);

        // 磁盘上的目录按 yyyy/MM 分桶（不是一个目录装下所有图）
        assertThat(Files.exists(MEDIA_ROOT)).isTrue();
        try (var listed = Files.walk(MEDIA_ROOT)) {
            assertThat(listed.filter(Files::isRegularFile).count())
                    .as("原图 + 缩略图 = 2 个文件（同一张图）").isGreaterThanOrEqualTo(2);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("缩略图按长边 64 缩放（本类把 max-thumb-side 调成了 64）")
    void thumbnailRespectsConfiguredLongSide() throws IOException {
        createdMedia.add(number(ok(upload("wide.png", png(200, 50), alice.token())).get("media_id")));
        Map<String, Object> again = ok(upload("wide2.png", png(200, 50), alice.token()));
        createdMedia.add(number(again.get("media_id")));

        byte[] thumb = download(number(again.get("media_id")), true, alice.token()).getBody();
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(thumb));
        assertThat(image).isNotNull();
        assertThat(Math.max(image.getWidth(), image.getHeight())).isEqualTo(64);
        // 200x50 → 长边 64 → 64x16
        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(image.getHeight()).isEqualTo(16);
    }

    @Test
    @DisplayName("webp：能上传能下载，但宽高为 null、thumb_url 与 url 相同（当前 JVM 解不了 webp）")
    void webpDegradesGracefully() {
        Map<String, Object> uploaded = ok(upload("anim.webp", webp(), alice.token()));
        long mediaId = number(uploaded.get("media_id"));
        createdMedia.add(mediaId);

        assertThat(uploaded.get("mime")).isEqualTo("image/webp");
        assertThat(uploaded.get("width")).as("解不出来就得是 null，不能是 0").isNull();
        assertThat(uploaded.get("height")).isNull();
        assertThat(uploaded.get("thumb_url")).isEqualTo(uploaded.get("url"));

        // ?thumb=1 回原图而不是 404（客户端把 thumb_url 当主要地址用）
        ResponseEntity<byte[]> thumb = download(mediaId, true, alice.token());
        assertThat(thumb.getStatusCode().value()).isEqualTo(200);
        assertThat(thumb.getHeaders().getContentType().toString()).isEqualTo("image/webp");
    }

    // ================================================================ 错误路径

    @Test
    @DisplayName("格式与大小：非图片 40013、应用层超限 40014、容器层超限也是 40014（不是 50000）")
    void rejectsUnsupportedAndOversize() {
        // ---------- 非图片 ----------
        ResponseEntity<Map<String, Object>> text = upload("note.txt",
                "我不是图片".getBytes(StandardCharsets.UTF_8), alice.token());
        assertThat(text.getStatusCode().value()).as("业务失败是 HTTP 200").isEqualTo(200);
        assertThat(number(body(text).get("code"))).isEqualTo(40013L);

        // ---------- 应用层上限 ----------
        byte[] justOver = pngPaddedTo(MAX_APP_BYTES + 128);
        ResponseEntity<Map<String, Object>> tooBig = upload("big.png", justOver, alice.token());
        assertThat(tooBig.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(tooBig).get("code"))).isEqualTo(40014L);

        // ---------- 容器上限（请求根本到不了控制器） ----------
        byte[] farOver = pngPaddedTo(MAX_CONTAINER_BYTES + 4096);
        ResponseEntity<Map<String, Object>> containerRejected =
                upload("huge.png", farOver, alice.token());
        assertThat(number(body(containerRejected).get("code")))
                .as("被容器拦下也必须是我们自己的错误码：50000 会让客户端一直重试一个超限的文件")
                .isEqualTo(40014L);
    }

    @Test
    @DisplayName("参数与权限：没带 file 40001、没有凭证 40101、不存在的 media_id 40008")
    void rejectsBadRequests() {
        MultiValueMap<String, Object> empty = new LinkedMultiValueMap<>();
        ResponseEntity<Map<String, Object>> noFile =
                rest.exchange(url("/v1/media"), HttpMethod.POST,
                        new HttpEntity<>(empty, multipartHeaders(alice.token())), JSON_MAP);
        assertThat(number(body(noFile).get("code"))).isEqualTo(40001L);

        ResponseEntity<Map<String, Object>> anonymous = rest.exchange(url("/v1/media/123"),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), JSON_MAP);
        assertThat(anonymous.getStatusCode().value()).as("缺凭证是 401").isEqualTo(401);
        assertThat(number(body(anonymous).get("code"))).isEqualTo(40101L);

        ResponseEntity<Map<String, Object>> missing =
                downloadEnvelope("/v1/media/999999999999", alice.token());
        assertThat(missing.getStatusCode().value()).isEqualTo(200);
        assertThat(number(body(missing).get("code"))).isEqualTo(40008L);
    }

    // ================================================================ 工具

    private Account newAccount() {
        Map<String, Object> data = ok(post("/v1/auth/register",
                Map.of("handle", HANDLE, "password", PASSWORD, "display_name", "IT 媒体用户"), null));
        refreshTokens.add((String) data.get("refresh_token"));
        return new Account(number(data.get("actor_id")), HANDLE, (String) data.get("access_token"));
    }

    private ResponseEntity<Map<String, Object>> upload(String filename, byte[] bytes, String bearer) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return rest.exchange(url("/v1/media"), HttpMethod.POST,
                new HttpEntity<>(body, multipartHeaders(bearer)), JSON_MAP);
    }

    private HttpHeaders multipartHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        headers.set("X-TM-Device-Id", "it-junit");
        return headers;
    }

    /** 下载二进制（成功路径）。
     *
     * <p>路径由 id 拼成（见 {@code uploadThenDownload} 里的说明），而不是用响应里的绝对 URL。
     */
    private ResponseEntity<byte[]> download(long mediaId, boolean thumb, String bearer) {
        String path = "/v1/media/" + mediaId + (thumb ? "?thumb=1" : "");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + bearer);
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    /** 以信封形式取下载接口的失败响应（失败时它是 JSON，不是流）。 */
    private ResponseEntity<Map<String, Object>> downloadEnvelope(String path, String bearer) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(bearer)), JSON_MAP);
    }

    private ResponseEntity<Map<String, Object>> post(String path, Object body, String bearer) {
        HttpHeaders headers = headers(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(com.tm.im.common.json.Json.write(body), headers), JSON_MAP);
    }

    private HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.set("Authorization", "Bearer " + bearer);
        }
        headers.set("X-TM-Device-Id", "it-junit");
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + httpPort + path;
    }

    // ---------------------------------------------------------------- 图片夹具

    private static byte[] png(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.drawLine(0, 0, width - 1, height - 1);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 造一个「正好达到指定字节数」的 PNG：在小图后面补零。
     *
     * <p>PNG 解码器读到 {@code IEND} 就停（后续字节按规范应当被忽略），
     * 所以补零既能控制大小，又保持「内容是一张合法图片」。
     * 这样体积断言就不必依赖某张图的压缩率——那种断言会随 JDK 版本漂移。
     */
    private static byte[] pngPaddedTo(int totalBytes) {
        byte[] base = png(8, 8);
        if (base.length >= totalBytes) {
            throw new IllegalStateException("基准图片已经比目标大小还大: " + base.length);
        }
        byte[] out = new byte[totalBytes];
        System.arraycopy(base, 0, out, 0, base.length);
        return out;
    }

    /**
     * 最小 webp 探针（见 {@code MediaServiceTest} 的同类夹具注释）：
     * 只有 RIFF/WEBP 头，足以走完「识别 → 存取 → 回原图」这条路径。
     */
    private static byte[] webp() {
        byte[] bytes = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        bytes[4] = 4;
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }

    // ---------------------------------------------------------------- 断言工具

    private static void assertSnakeCase(Map<String, Object> data, String... expectedKeys) {
        assertThat(data.keySet()).contains(expectedKeys);
        for (String key : data.keySet()) {
            assertThat(key).as("响应里出现了 camelCase 键（应为 %s）", snake(key)).isEqualTo(snake(key));
        }
    }

    private static String snake(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> ok(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode().value()).as("期望成功，实际 %s", response.getBody())
                .isEqualTo(200);
        Map<String, Object> envelope = body(response);
        assertThat(number(envelope.get("code"))).as("期望 code=0，实际 %s", envelope).isZero();
        Map<String, Object> data = map(envelope.get("data"));
        assertThat(data).isNotNull();
        return data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).as("期望 JSON 对象，实际 %s", value).isInstanceOf(Map.class);
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static Map<String, Object> body(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response.getBody();
        assertThat(body).as("响应体不是 JSON 对象").isNotNull();
        return new LinkedHashMap<>(body);
    }

    private static long number(Object value) {
        assertThat(value).as("期望数字字段，实际 %s", value).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }
}
