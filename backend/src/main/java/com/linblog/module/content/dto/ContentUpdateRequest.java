package com.linblog.module.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** PATCH /api/v1/admin/contents/{id} 请求体（§5.3）：null 字段不更新。 */
public record ContentUpdateRequest(
        String title,
        String introduce,
        String body,
        Long coverMediaId,
        @JsonProperty("labelIds") List<Long> labelIds,
        Boolean isPinned) {
}
