package com.linblog.module.content.dto;

/** PATCH /api/v1/admin/contents/{id}/state 请求体（§5.3）。 */
public record ContentStateRequest(String state) {
}
