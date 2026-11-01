package com.linblog.module.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/v1/admin/labels 与 PATCH /api/v1/admin/labels/{id} 请求体（§5.3）。 */
public record LabelRequest(
        @NotBlank(message = "名称不能为空")
        @Size(max = 30, message = "名称不能超过 30 字")
        String name) {
}
