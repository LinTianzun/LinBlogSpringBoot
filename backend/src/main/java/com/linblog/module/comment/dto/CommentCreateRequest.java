package com.linblog.module.comment.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * POST /comments（§5.5）：{targetType, targetId, parentId?, replyToCommentId?, body≤2000}。
 * 文档 §5.5 载荷原文为 parent_id，实现统一 camelCase（前端同发 parentId，契约一致）；
 * @JsonAlias 保留 parent_id 蛇形兼容。targetType 走 §4.2 白名单：article/image/diary/guestbook。
 */
public record CommentCreateRequest(
        String targetType,
        Long targetId,
        @JsonAlias({"parent_id"}) Long parentId,
        @JsonAlias({"reply_to_comment_id"}) Long replyToCommentId,
        String body) {
}
