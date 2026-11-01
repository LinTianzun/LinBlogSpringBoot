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
}
