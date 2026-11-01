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

    /** 点赞计数（M3 interactions 联动）。 */
    @Update("UPDATE diaries SET praises = praises + 1 WHERE id = #{id}")
    int incrPraises(@Param("id") Long id);

    /** 取消点赞：GREATEST 防并发回退为负。 */
    @Update("UPDATE diaries SET praises = GREATEST(praises - 1, 0) WHERE id = #{id}")
    int decrPraises(@Param("id") Long id);
}
