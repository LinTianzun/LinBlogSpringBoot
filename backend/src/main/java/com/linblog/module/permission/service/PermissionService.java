package com.linblog.module.permission.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.config.CacheConfig;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.mapper.RolePermissionMapper;
import com.linblog.security.AuthPrincipal;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限服务（§7.1）：super_admin 键集硬编码；其余角色 role_permissions 覆盖值，
 * 无覆盖用默认矩阵；permMatrix Caffeine 缓存，矩阵保存后 invalidateAll。
 */
@Service
public class PermissionService {

    /** 缓存值包装：避免对 Set 做泛型强转。 */
    public record RoleKeys(Set<String> keys) {
    }

    private final RolePermissionMapper rolePermissionMapper;
    private final CacheManager cacheManager;
    private final SystemLogService systemLogService;

    public PermissionService(RolePermissionMapper rolePermissionMapper, CacheManager cacheManager,
                             SystemLogService systemLogService) {
        this.rolePermissionMapper = rolePermissionMapper;
        this.cacheManager = cacheManager;
        this.systemLogService = systemLogService;
    }

    /** 角色可用的权限键集合。未知角色返回空集。 */
    public Set<String> keysForRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return Set.of();
        }
        if (PermissionKeys.SUPER_ADMIN.equals(roleCode)) {
            return PermissionKeys.ALL_KEYS;
        }
        Cache cache = cacheManager.getCache(CacheConfig.PERM_MATRIX);
        RoleKeys cached = cache.get(roleCode, RoleKeys.class);
        if (cached != null) {
            return cached.keys();
        }
        Set<String> keys = loadFromDb(roleCode);
        cache.put(roleCode, new RoleKeys(keys));
        return keys;
    }

    private Set<String> loadFromDb(String roleCode) {
        List<String> rows = rolePermissionMapper.selectKeysByRole(roleCode);
        if (rows.isEmpty()) {
            return PermissionKeys.defaultKeysFor(roleCode);
        }
        Set<String> known = new LinkedHashSet<>();
        for (String key : rows) {
            if (PermissionKeys.ALL_KEYS.contains(key)) {
                known.add(key);
            }
        }
        return Set.copyOf(known);
    }

    public void invalidateAll() {
        cacheManager.getCache(CacheConfig.PERM_MATRIX).clear();
    }

    /** 权限判定：不满足抛 40300。 */
    public void require(AuthPrincipal principal, String permissionKey) {
        if (principal == null || !principal.has(permissionKey)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * 保存权限矩阵（§5.2 PUT /permissions/matrix）：仅超管；
     * 拒绝 super_admin 行、受保护键、未知键/角色；全量 delete+insert；保存后失效缓存 + 系统日志。
     *
     * @return 各角色写入的键数
     */
    @Transactional
    public Map<String, Integer> saveMatrix(AuthPrincipal operator, Map<String, List<String>> matrix, String ip) {
        if (operator == null || !operator.superAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅超级管理员可修改权限矩阵");
        }
        if (matrix == null || matrix.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "matrix 不能为空");
        }
        for (Map.Entry<String, List<String>> entry : matrix.entrySet()) {
            String role = entry.getKey();
            if (!PermissionKeys.isKnownRole(role)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知角色: " + role);
            }
            if (PermissionKeys.SUPER_ADMIN.equals(role)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "super_admin 权限不可修改");
            }
            for (String key : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                if (!PermissionKeys.ALL_KEYS.contains(key)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "未知权限键: " + key);
                }
                if (PermissionKeys.PROTECTED_KEYS.contains(key)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "受保护权限键不可修改: " + key);
                }
            }
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : matrix.entrySet()) {
            Set<String> distinct = new LinkedHashSet<>(
                    entry.getValue() == null ? List.<String>of() : entry.getValue());
            rolePermissionMapper.deleteByRole(entry.getKey());
            for (String key : distinct) {
                rolePermissionMapper.insert(entry.getKey(), key);
            }
            counts.put(entry.getKey(), distinct.size());
        }
        invalidateAll();
        systemLogService.record(operator.uid(), "permission", "matrix_update", null, null,
                "roles=" + new ArrayList<>(matrix.keySet()), ip);
        return counts;
    }
}
