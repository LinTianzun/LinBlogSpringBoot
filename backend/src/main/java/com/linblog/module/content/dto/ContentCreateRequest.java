package com.linblog.module.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** POST /api/v1/admin/contents 请求体（§5.3）。 */
public record ContentCreateRequest(
        String contentType,
        String title,
        String introduce,
        String body,
        Long coverMediaId,
        @JsonProperty("labelIds") List<Long> labelIds,
        String state) {
}
