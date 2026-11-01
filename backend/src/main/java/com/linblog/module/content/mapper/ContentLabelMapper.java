package com.linblog.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.content.entity.ContentLabel;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContentLabelMapper extends BaseMapper<ContentLabel> {

    @Delete("DELETE FROM content_labels WHERE content_id = #{contentId}")
    int deleteByContentId(@Param("contentId") Long contentId);

    @Delete("DELETE FROM content_labels WHERE label_id = #{labelId}")
    int deleteByLabelId(@Param("labelId") Long labelId);
}
