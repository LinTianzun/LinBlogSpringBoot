package com.linblog.module.guestbook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.linblog.module.comment.dto.CommentView;

import java.time.LocalDateTime;

/**
 * POST /guestbook 结果：kind=entry（一级留言，entry 非空）或 reply（回复，comment 非空）。
 * 文档未定义响应体，此结构让前端区分两种落库路径。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuestbookCreateResult(String kind, GuestbookView entry, CommentView comment) {

    public static GuestbookCreateResult entry(GuestbookView view) {
        return new GuestbookCreateResult("entry", view, null);
    }

    public static GuestbookCreateResult reply(CommentView view) {
        return new GuestbookCreateResult("reply", null, view);
    }
}
