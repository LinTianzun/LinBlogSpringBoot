package com.linblog.module.comment.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /comments（§5.5）：{targetType, targetId, parent_id?, replyToCommentId?, body≤2000}。
 * targetType 走 §4.2 白名单：article/image/diary/guestbook（服务端映射到表）。
 */
public record CommentCreateRequest(
        String targetType,
        Long targetId,
        @JsonProperty("parent_id") @JsonAlias({"parentId"}) Long parentId,
        @JsonAlias({"reply_to_comment_id"}) Long replyToCommentId,
        String body) {
}
