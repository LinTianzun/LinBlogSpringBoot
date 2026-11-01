package com.linblog.module.diary.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.diary.entity.Diary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DiaryMapper extends BaseMapper<Diary> {

    @Update("UPDATE diaries SET views = views + 1 WHERE id = #{id}")
    int incrViews(@Param("id") Long id);
}
