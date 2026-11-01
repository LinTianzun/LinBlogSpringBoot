package com.linblog.module.comment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论视图：portal 两段式（rootComments/replyComments）与留言墙树共用。
 * liked 仅在请求方已登录时可能为 true；subReplies 仅留言墙树形返回时填充。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommentView(
        Long id,
        String targetType,
        Long targetId,
        Long authorId,
        String authorName,
        Long parentId,
        Long replyToCommentId,
        Long replyToUserId,
        String replyToUserName,
        String body,
        long praiseCount,
        boolean liked,
        LocalDateTime createdAt,
        List<CommentView> subReplies) {

    public static CommentView of(Long id, String targetType, Long targetId, Long authorId, String authorName,
                                 Long parentId, Long replyToCommentId, Long replyToUserId, String replyToUserName,
                                 String body, long praiseCount, boolean liked, LocalDateTime createdAt) {
        return new CommentView(id, targetType, targetId, authorId, authorName, parentId, replyToCommentId,
                replyToUserId, replyToUserName, body, praiseCount, liked, createdAt, null);
    }
}
