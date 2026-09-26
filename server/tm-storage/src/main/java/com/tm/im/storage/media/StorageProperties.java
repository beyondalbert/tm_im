package com.tm.im.storage.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 媒体（图片）存储配置 —— {@code deploy/conf/application-external.yml.example} 的
 * {@code tm.storage} 段的代码侧。
 *
 * <p><b>它与模板有机器校验</b>（{@code tools/verify_config_template.py}）：一旦这个类存在，
 * 该前缀下的键就进入严格模式——模板里多一个键（拼错）会失败，代码里多一个键
 * 而模板没写（运维不知道它存在）也会失败。所以本类的每个字段都必须在模板里有一行，
 * 且<b>默认值逐字相同</b>。
 *
 * <p><b>为什么不放进 tm-core</b>：{@code media-root} 只有存储适配器（
 * {@link com.tm.im.storage.media.LocalFsMediaStore}）需要，它是基础设施细节。
 * 而 {@code max-size-bytes} / {@code max-thumb-side} 与 {@code media-public-base}
 * 由 {@code MediaService} 读取——{@code tm-core} 依赖 {@code tm-storage}
 * （依赖方向是 {@code tm-common ← tm-domain ← tm-storage ← tm-core}），
 * 所以一个类放在这里两边都能用，不必为了分层把一个配置项拆成两个前缀
 * （拆开的话「上传上限」与「存储位置」就会在两个地方各自漂移）。
 */
@ConfigurationProperties(prefix = "tm.storage")
public class StorageProperties {

    /**
     * 存储实现：{@code local} | {@code oss} | {@code s3}。
     *
     * <p>当前只实现 {@code local}，另外两个取值会在启动时被拒绝（见
     * {@code LocalFsMediaStore} 的构造校验）。留给它们的位置是刻意的：
     * 若类型在这里不做校验，配成 {@code oss} 时会静默地按 local 跑起来——
     * 于是「图片存到了本机磁盘上」这件事要在多实例部署后才被发现
     * （每个实例只看得见自己那份文件）。
     */
    private String type = "local";

    /** 本地存储根目录（{@code type=local} 时使用）。相对路径按进程工作目录解析。 */
    private String mediaRoot = "./data/media";

    /**
     * 对外 URL 前缀（{@code url} / {@code thumb_url} 的拼接基址）。
     *
     * <p>生产应当填对外域名（走 CDN 时填 CDN 域名），而不是 {@code localhost}——
     * 它是<b>写进 JSON 交给客户端</b>的值，客户端会拿它直接发起请求。
     */
    private String mediaPublicBase = "http://localhost:8080/v1/media";

    /** 对象存储 endpoint（{@code type} 非 local 时使用；当前未实现，见 {@link #type}）。 */
    private String endpoint;

    /** 对象存储 bucket（同上）。 */
    private String bucket;

    /** 对象存储 access key（同上）。 */
    private String accessKey;

    /** 对象存储 secret key（同上）。 */
    private String secretKey;

    /**
     * 单张图片的字节上限（07-errors-limits.md §2.1 规定 10MB，超限回 40014）。
     *
     * <p><b>它必须与 Servlet 的 {@code spring.servlet.multipart.max-file-size} 一致</b>：
     * 那个值决定「请求能不能进到应用」，这个值决定「进来之后算不算超限」。
     * 若 multipart 的限制更松，超出本值的文件会先被完整读进内存再被拒（白花内存）；
     * 更紧的话，本值永远不会生效，而错误码会变成容器抛出的那一个。
     */
    private int maxSizeBytes = 10 * 1024 * 1024;

    /**
     * 缩略图长边像素（客户端用 {@code ?thumb=1} 取）。
     *
     * <p>320 是「列表里的方形小图」在 2x 屏上的尺寸：再大就与直接用原图没区别，
     * 再小则在高分屏上明显发虚。它同时决定了每张缩略图的服务端 CPU 开销
     * （缩放一次 + JPEG 编码一次），所以不该由客户端传参指定。
     */
    private int maxThumbSide = 320;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMediaRoot() {
        return mediaRoot;
    }

    public void setMediaRoot(String mediaRoot) {
        this.mediaRoot = mediaRoot;
    }

    public String getMediaPublicBase() {
        return mediaPublicBase;
    }

    public void setMediaPublicBase(String mediaPublicBase) {
        this.mediaPublicBase = mediaPublicBase;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public int getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(int maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public int getMaxThumbSide() {
        return maxThumbSide;
    }

    public void setMaxThumbSide(int maxThumbSide) {
        this.maxThumbSide = maxThumbSide;
    }

    /**
     * 凭据字段（{@code secret-key} / {@code access-key}）脱敏。
     *
     * <p>属性类被 Spring 的 Actuator 或启动日志整份打印是常见的事，
     * 而这两个字段是能用对象存储的真凭据。这里只回显「有没有配」，
     * 不回显值本身——排查配置问题只需要知道这一点。
     */
    @Override
    public String toString() {
        return "StorageProperties{type=" + type
                + ", mediaRoot=" + mediaRoot
                + ", mediaPublicBase=" + mediaPublicBase
                + ", endpoint=" + endpoint
                + ", bucket=" + bucket
                + ", accessKey=" + (accessKey == null || accessKey.isEmpty() ? "" : "<已配置>")
                + ", secretKey=" + (secretKey == null || secretKey.isEmpty() ? "" : "<已配置>")
                + ", maxSizeBytes=" + maxSizeBytes
                + ", maxThumbSide=" + maxThumbSide + '}';
    }
}
