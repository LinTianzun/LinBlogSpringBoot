package com.linblog.module.diary.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** POST /api/v1/admin/diaries 请求体（§5.4）：weather 8 值白名单由服务端校验。 */
public record DiaryCreateRequest(
        @JsonProperty("diaryDate") LocalDate diaryDate,
        String weather,
        String mood,
        String body,
        String state) {
}
