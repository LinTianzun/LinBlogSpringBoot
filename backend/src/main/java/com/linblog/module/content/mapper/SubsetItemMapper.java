package com.linblog.module.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.content.entity.SubsetItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SubsetItemMapper extends BaseMapper<SubsetItem> {

    @Delete("DELETE FROM subset_items WHERE subset_id = #{subsetId}")
    int deleteBySubsetId(@Param("subsetId") Long subsetId);
}
