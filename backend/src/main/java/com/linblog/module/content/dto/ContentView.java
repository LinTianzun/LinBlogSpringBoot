package com.linblog.module.content.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** 内容列表/详情响应（§5.3）：列表不带 body，详情带；admin 视图含 plainText。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentView(
        Long id,
        String contentType,
        String title,
        String introduce,
        String body,
        String plainText,
        Long coverMediaId,
        String state,
        LocalDateTime publishedAt,
        Integer views,
        Integer praises,
        Integer commentsCount,
        Boolean isPinned,
        Long authorId,
        String authorName,
        List<Long> labelIds,
        List<String> labels,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 列表视图：去掉 body/plainText（列表不回传大字段）。 */
    public static ContentView brief(ContentView full) {
        return new ContentView(full.id(), full.contentType(), full.title(), full.introduce(),
                null, null, full.coverMediaId(), full.state(), full.publishedAt(),
                full.views(), full.praises(), full.commentsCount(), full.isPinned(),
                full.authorId(), full.authorName(), full.labelIds(), full.labels(),
                full.createdAt(), full.updatedAt());
    }
}
