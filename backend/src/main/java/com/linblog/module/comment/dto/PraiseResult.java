package com.linblog.module.comment.dto;

/** 点赞 toggle 结果（§5.5）：{active, count}。 */
public record PraiseResult(boolean active, long count) {
}
