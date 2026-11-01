package com.linblog.module.diary.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 日记响应（§5.4）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiaryView(
        Long id,
        Long authorId,
        String authorName,
        LocalDate diaryDate,
        String weather,
        String mood,
        String body,
        String state,
        Integer views,
        Integer praises,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
