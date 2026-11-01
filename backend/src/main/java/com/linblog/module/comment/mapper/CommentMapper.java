package com.linblog.module.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.comment.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /** 评论点赞 +1（praise_count 冗余列）。 */
    @Update("UPDATE comments SET praise_count = praise_count + 1 WHERE id = #{id}")
    int incrPraise(@Param("id") Long id);

    /** 取消点赞：GREATEST 防并发回退为负。 */
    @Update("UPDATE comments SET praise_count = GREATEST(praise_count - 1, 0) WHERE id = #{id}")
    int decrPraise(@Param("id") Long id);
}
