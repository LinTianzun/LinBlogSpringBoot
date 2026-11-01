package com.linblog.module.comment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/** 举报工作台行（§5.5）：分页含评论与作者摘要。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportView(
        Long id,
        Long commentId,
        String commentBody,
        Long commentAuthorId,
        String commentAuthorName,
        String commentStatus,
        Long reporterId,
        String reporterName,
        String reason,
        String detail,
        String status,
        String handledByName,
        String handleNote,
        LocalDateTime createdAt,
        LocalDateTime handledAt) {
}
