// 源: deploy/sql/01-schema.sql  sha256[:16]=6e25d69f6d539a88
package com.tm.im.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tm.im.domain.entity.FeedItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 广场收件箱 的基础 CRUD。
 *
 * <p>由 <code>tools/gen_entities.py</code> 生成，请勿手工编辑。
 * 复杂查询写在仓储实现里（用 Wrapper），不要往这里加自定义 SQL——
 * 分片表上任何不带 conv_id 的语句都会退化成 16 张表的广播查询。
 *
 * <p><b>{@code selectById} / {@code deleteById} 在本表上不可用</b>（联合主键），
 * 原因见其实体类注释。请改用 {@code selectList(Wrapper)} 配合显式条件。
 */
@Mapper
public interface FeedItemMapper extends BaseMapper<FeedItem> {
}
