package com.linblog.module.guestbook.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/** POST /guestbook（§5.6）：{body, replyToId?}。replyToId 非空 = 回复（走 comments 表）。 */
public record GuestbookCreateRequest(String body, @JsonAlias({"reply_to_id"}) Long replyToId) {
}
