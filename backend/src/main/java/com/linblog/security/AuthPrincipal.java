package com.linblog.security;

import java.util.Set;

/**
 * 认证主体：uid + 角色 + 该角色当前可用权限键集合（超管为全键）。
 * 存入 SecurityContext；控制器/服务用 has(key) 做 §5 权限判定。
 */
public record AuthPrincipal(
        long uid,
        String username,
        String role,
        boolean superAdmin,
        Set<String> permissions) {

    public boolean has(String permissionKey) {
        return permissions != null && permissions.contains(permissionKey);
    }

    public boolean hasAny(String... keys) {
        for (String k : keys) {
            if (has(k)) {
                return true;
            }
        }
        return false;
    }
}
