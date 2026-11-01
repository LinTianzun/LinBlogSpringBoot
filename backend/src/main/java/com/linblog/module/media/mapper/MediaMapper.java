package com.linblog.module.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.media.entity.Media;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MediaMapper extends BaseMapper<Media> {

    /** 封面引用数：除指定内容外，还有多少非 deleted 内容引用该 media（删除内容时判定是否转回收站）。 */
    @Select("SELECT COUNT(*) FROM content WHERE cover_media_id = #{mediaId} AND id <> #{excludeContentId} AND state <> 'deleted'")
    long countCoverReferences(@Param("mediaId") Long mediaId, @Param("excludeContentId") Long excludeContentId);
}
