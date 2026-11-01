package com.linblog.module.comment.dto;

/** POST /interactions（§5.5）：{targetType:content|diary, targetId} 点赞 toggle。 */
public record InteractionRequest(String targetType, Long targetId) {
}
