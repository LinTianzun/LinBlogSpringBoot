package com.linblog.module.comment.dto;

/** PATCH /admin/comment-reports/{id}（§5.5）：{action: resolved|dismissed, note≤200}。 */
public record ReportHandleRequest(String action, String note) {
}
