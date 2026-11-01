package com.linblog.module.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** PUT /api/v1/admin/subsets/{id}/items：全量替换合集条目及顺序。 */
public record SubsetItemsRequest(
        @JsonProperty("contentIds") List<Long> contentIds) {
}
