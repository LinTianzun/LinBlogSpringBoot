package com.linblog.module.diary.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/** PATCH /api/v1/admin/diaries/{id} 请求体（§5.4）：null 字段不更新。 */
public record DiaryUpdateRequest(
        @JsonProperty("diaryDate") LocalDate diaryDate,
        String weather,
        String mood,
        String body,
        String state) {
}
