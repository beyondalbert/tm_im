package com.tm.im.domain.media;

import java.util.Optional;

/**
 * 二进制对象存储的<b>端口</b>（图片字节放这里，元数据放 {@code media} 表）。
 *
 * <p><b>为什么端口在领域层、实现在仓储层</b>：与 {@code MessageRepository} 同一个理由——
 * 「图片存在哪」是基础设施决定（本地 FS / OSS / S3），而「一张图能不能被这个用户看到」
 * 是业务规则。把两者混在一个类里，业务规则就会跟着存储实现一起被替换掉。
 * 端口的形状只描述「按 key 存取一段字节」，不含任何厂商概念（没有 bucket、
 * 没有签名 URL、没有分片上传）。
 *
 * <p><b>为什么是 {@code byte[]} 而不是 {@code InputStream}</b>：上行的 {@code put}
 * 无论如何都要在内存里拿到完整字节——图片要先验格式（magic bytes）、
 * 读尺寸、拒解压炸弹，这三件事都需要字节在手（见 {@code MediaService}）。
 * 既然上行已经是「一次拿到全部」，下行也照同一个形状，端口就只有一种用法，
 * 实现方不必考虑「调用者有没有关流」。代价是单次上传/下载峰值内存 = 一个文件
 * （上限由 {@code tm.storage.max-size-bytes} 与 Servlet 的 multipart 限制共同约束）。
 * 若将来要放视频（GB 级），这个端口要改成流式——那是一次显式的破坏性变更，
 * 而不是「顺手让它也支持流」。
 *
 * <p><b>key 由调用方生成</b>，不由实现方生成：{@code media.object_key} 是要落库的，
 * 落库之后才知道 key 的话，就必然出现「存了但没记住」的孤儿文件。
 * 调用方（{@code MediaService}）用「服务端生成的 id + 由内容推断出的扩展名」拼 key，
 * 因此 key 里不可能出现用户输入的任何片段（路径穿越在结构上不成立）。
 */
public interface MediaStore {

    /**
     * 写入（覆盖同名 key）。
     *
     * @throws com.tm.im.common.error.TmException 50003（存储不可用）
     */
    void put(String key, byte[] content);

    /**
     * 读取。
     *
     * <p>返回 {@code Optional} 而不是抛异常：key 是从 {@code media} 表读出来的，
     * 而「库里有一行、盘上没这个文件」是一种<b>已知可能</b>的状态
     * （磁盘被清理过、迁移漏了文件、写盘成功但删库回滚）。它不是编程错误，
     * 需要翻译成对外的一个码（40008），所以用返回值表达。
     */
    Optional<byte[]> get(String key);

    /** 删除。key 不存在时静默返回（幂等）——补偿逻辑会重复调用它。 */
    void delete(String key);
}
