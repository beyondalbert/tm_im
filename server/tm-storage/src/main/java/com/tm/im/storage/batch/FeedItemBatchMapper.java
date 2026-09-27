package com.tm.im.storage.batch;

import com.tm.im.domain.entity.FeedItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 写扩散的<b>多行插入</b>——本包内唯一一个手写的 Mapper（其余 {@code *Mapper} 都是生成的）。
 *
 * <p>为什么值得破一次例：写扩散一次要往 {@code feed_item} 插「作者的好友数」行。
 * 用 MyBatis-Plus 的 {@code BaseMapper#insert} 逐行插，5000 个好友就是 5000 次往返，
 * 而这条路径在发帖的请求之外（异步），慢的是线程池和数据库连接，
 * 不是用户；但它会把「异步」这个设计意图悄悄变成「把连接池占满」。
 *
 * <p>为什么不可用生成器：生成的 Mapper 只有 {@code BaseMapper} 那套方法，
 * 而它刻意不含自定义 SQL（见生成器注释：分片表上不带 conv_id 的自定义 SQL
 * 会退化成广播查询）。{@code feed_item} 是<b>非分片表</b>，
 * 多条 {@code VALUES} 插进同一张物理表没有那个风险。
 *
 * <p>它刻意<b>不在</b> {@code com.tm.im.storage.mapper} 包下：那个包的约定是
 * 「里面的文件全部由 tools/gen_entities.py 生成」，而生成器会把不认识的残留文件删掉
 * （它的 --check 会把它们报成「生成器已不产出」）。把手工 SQL 放进那个包，
 * 下一次重跑生成器时它就会消失。
 *
 * <p>{@code items} 必须非空且不超过调用方给定的分块大小——
 * 这不是接口能强制的，由 {@code FeedItemRepositoryImpl} 分块保证。
 */
@Mapper
public interface FeedItemBatchMapper {

    @Insert("<script>"
            + "INSERT INTO feed_item (owner_id, score, post_id, author_id) VALUES "
            + "<foreach collection='items' item='it' separator=','>"
            + "(#{it.ownerId}, #{it.score}, #{it.postId}, #{it.authorId})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("items") List<FeedItem> items);
}
