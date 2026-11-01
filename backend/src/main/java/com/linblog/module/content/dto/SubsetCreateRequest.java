package com.linblog.module.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** POST /api/v1/admin/subsets 请求体。 */
public record SubsetCreateRequest(
        @jakarta.validation.constraints.NotBlank(message = "名称不能为空")
        @jakarta.validation.constraints.Size(max = 50, message = "名称不能超过 50 字")
        String name,
        @jakarta.validation.constraints.Size(max = 200, message = "描述不能超过 200 字")
        String description,
        Long coverMediaId,
        @JsonProperty("contentIds") List<Long> contentIds) {
}
