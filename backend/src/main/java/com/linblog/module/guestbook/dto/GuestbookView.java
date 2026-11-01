package com.linblog.module.guestbook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.linblog.module.comment.dto.CommentView;

import java.time.LocalDateTime;
import java.util.List;

/** GET /guestbook（§5.6）：全部 visible 一级留言 + 两级回复（回复来自 comments 表）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuestbookView(
        Long id,
        Long userId,
        String userName,
        String body,
        LocalDateTime createdAt,
        List<CommentView> replies) {
}
