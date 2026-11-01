package com.linblog.module.comment.dto;

/** POST /comments/{id}/report（§5.5）：reason ∈ 白名单（§3.3），detail ≤200。 */
public record ReportCreateRequest(String reason, String detail) {
}
