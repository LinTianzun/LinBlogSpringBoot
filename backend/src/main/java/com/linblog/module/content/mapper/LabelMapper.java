package com.linblog.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.content.entity.Label;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LabelMapper extends BaseMapper<Label> {

    /** 某内容的全部标签 id。 */
    @Select("SELECT label_id FROM content_labels WHERE content_id = #{contentId}")
    List<Long> labelIdsOfContent(@Param("contentId") Long contentId);

    /** 标签被引用数（删除前检查）。 */
    @Select("SELECT COUNT(*) FROM content_labels WHERE label_id = #{labelId}")
    long countReferences(@Param("labelId") Long labelId);

    /** 某内容在指定标签集合中的引用数（替换标签前清理用）。 */
    @Select("SELECT COUNT(*) FROM content_labels WHERE content_id = #{contentId} AND label_id IN (${labelIds})")
    long countReferencesIn(@Param("contentId") Long contentId, @Param("labelIds") String labelIds);
}
