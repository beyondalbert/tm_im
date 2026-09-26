package com.tm.im.domain.repository;

import com.tm.im.domain.entity.Media;

import java.util.Optional;

/**
 * 媒体元数据仓储（{@code media} 表）。
 *
 * <p><b>为什么元数据要落库、字节不落库</b>：字节放对象存储时，数据库里必须有一份
 * 「这张图存在、属于谁、有多大、什么格式」的权威记录。否则：
 * <ul>
 *   <li>下载接口无法回答「这个 id 是不是一张图」（只能去问存储，于是把
 *       「不存在的资源」变成了存储层的一次 IO 与一个无法区分的错误）；</li>
 *   <li>消息里的 {@code media_id} 无从校验、无从展示尺寸（客户端要按宽高占位）；</li>
 *   <li>没有归属信息，将来做配额与清理时无据可依。</li>
 * </ul>
 *
 * <p>{@code media} 是<b>非分片表</b>（分片表只有 {@code message}），所以这里全部
 * 都是单表点查，没有 §8.7 那些「不带分片键就广播」的问题。
 */
public interface MediaRepository {

    /**
     * 写入一条元数据。
     *
     * <p><b>先落盘后落库</b>由调用方保证（见 {@code MediaService.upload}）：
     * 反过来的话，库里会先出现一行指向不存在文件的记录，而客户端拿到
     * {@code media_id} 就会立刻去引用它——那是一个必然 40008 的引用。
     * 顺序反过来最坏的结果只是盘上多一个没人引用的文件（可被清理任务回收）。
     */
    Media insert(Media media);

    Optional<Media> findById(long mediaId);

    /** 删除元数据（补偿上传失败时用；字节由 {@code MediaStore} 单独删）。 */
    void delete(long mediaId);
}
