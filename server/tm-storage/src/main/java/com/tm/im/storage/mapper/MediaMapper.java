// 源: deploy/sql/01-schema.sql  sha256[:16]=3b1df4f3ef040428
package com.tm.im.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tm.im.domain.entity.Media;
import org.apache.ibatis.annotations.Mapper;

/**
 * 媒体元数据（对象存储只存 key） 的基础 CRUD。
 *
 * <p>由 <code>tools/gen_entities.py</code> 生成，请勿手工编辑。
 * 复杂查询写在仓储实现里（用 Wrapper），不要往这里加自定义 SQL——
 * 分片表上任何不带 conv_id 的语句都会退化成 16 张表的广播查询。
 */
@Mapper
public interface MediaMapper extends BaseMapper<Media> {
}
