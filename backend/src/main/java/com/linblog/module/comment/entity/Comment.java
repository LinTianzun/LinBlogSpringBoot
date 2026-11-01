package com.linblog.module.comment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 统一评论（§3.3）：三端共用——content（文章/图库）/ diary / guestbook（留言墙回复）。
 * 两级封顶：parent_id NULL=一级；非 NULL=所属一级评论 id。
 */
@TableName("comments")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** content / diary / guestbook（数据库口径，article/image 在服务端映射为 content） */
    private String targetType;

    private Long targetId;

    private Long authorId;

    /** NULL=一级；非 NULL=所属一级评论 id（两级封顶） */
    private Long parentId;

    /** 楼中楼指向的被回复评论 */
    private Long replyToCommentId;

    /** 冗余被回复人，避免多跳查询 */
    private Long replyToUserId;

    /** 纯文本评论（不走富文本） */
    private String body;

    private Integer praiseCount;

    /** visible / deleted（逻辑删） */
    private String status;

    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Long getReplyToCommentId() { return replyToCommentId; }
    public void setReplyToCommentId(Long replyToCommentId) { this.replyToCommentId = replyToCommentId; }
    public Long getReplyToUserId() { return replyToUserId; }
    public void setReplyToUserId(Long replyToUserId) { this.replyToUserId = replyToUserId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Integer getPraiseCount() { return praiseCount; }
    public void setPraiseCount(Integer praiseCount) { this.praiseCount = praiseCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
