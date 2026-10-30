package com.linblog.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前认证主体；未认证返回 null。
 * 不用 @AuthenticationPrincipal：匿名时 principal 是字符串，避免类型转换歧义。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthPrincipal principal ? principal : null;
    }
}
