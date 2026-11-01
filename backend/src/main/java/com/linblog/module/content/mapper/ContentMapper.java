package com.linblog.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.content.entity.Content;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContentMapper extends BaseMapper<Content> {

    /** 浏览计数（§7.6）：并发可多记一次，不加锁。 */
    @Update("UPDATE content SET views = views + 1 WHERE id = #{id}")
    int incrViews(@Param("id") Long id);

    /** 点赞计数（M3 评论域联动预留）。 */
    @Update("UPDATE content SET praises = praises + 1 WHERE id = #{id}")
    int incrPraises(@Param("id") Long id);

    /** 取消点赞：GREATEST 防并发回退为负。 */
    @Update("UPDATE content SET praises = GREATEST(praises - 1, 0) WHERE id = #{id}")
    int decrPraises(@Param("id") Long id);

    /** 新增评论 +1（一级或楼中楼都算一条明细）。 */
    @Update("UPDATE content SET comments_count = comments_count + 1 WHERE id = #{id}")
    int incrCommentsCount(@Param("id") Long id);

    /** 级联逻辑删评论后回退冗余计数（§5.5）：GREATEST 防计数与明细不一致时出现负数。 */
    @Update("UPDATE content SET comments_count = GREATEST(comments_count - #{delta}, 0) WHERE id = #{id}")
    int decrCommentsCount(@Param("id") Long id, @Param("delta") int delta);
}
