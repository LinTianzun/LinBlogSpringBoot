package com.linblog.module.comment.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/** 评论点赞（§3.3）：comment_id + user_id 联合主键，无时间列。 */
@TableName("comment_praises")
public class CommentPraise {

    private Long commentId;

    private Long userId;

    public Long getCommentId() { return commentId; }
    public void setCommentId(Long commentId) { this.commentId = commentId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
}
