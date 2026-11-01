package com.linblog.module.permission;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §5.2 权限清单：33 键 8 组、受保护键、四角色默认矩阵。 */
class PermissionKeysTest {

    @Test
    void total33KeysIn8Groups() {
        assertEquals(8, PermissionKeys.GROUPS.size());
        assertEquals(33, PermissionKeys.ALL_KEYS.size());
        int grouped = PermissionKeys.GROUPS.values().stream().mapToInt(List::size).sum();
        assertEquals(33, grouped, "分组之和必须等于全部键");
        Set<String> distinct = new HashSet<>(PermissionKeys.ALL_KEYS);
        assertEquals(33, distinct.size(), "不允许重复键");
    }

    @Test
    void onlyTwoProtectedKeys() {
        assertEquals(Set.of("permission.edit", "user.assignAdmin"), PermissionKeys.PROTECTED_KEYS);
    }

    @Test
    void superAdminHasAll() {
        assertEquals(PermissionKeys.ALL_KEYS, PermissionKeys.defaultKeysFor("super_admin"));
    }

    @Test
    void adminHasAllExceptProtected() {
        Set<String> keys = PermissionKeys.defaultKeysFor("admin");
        assertEquals(31, keys.size());
        assertFalse(keys.contains("permission.edit"));
        assertFalse(keys.contains("user.assignAdmin"));
        assertTrue(keys.contains("article.manageAll"));
    }

    @Test
    void editorDefaultMatrix15Keys() {
        Set<String> keys = PermissionKeys.defaultKeysFor("editor");
        assertEquals(15, keys.size());
        // 文章组除 publish 外全部
        assertTrue(keys.containsAll(Set.of("article.viewAll", "article.viewOwn", "article.create",
                "article.manageOwn", "article.manageAll")));
        assertFalse(keys.contains("article.publish"));
        // 图库全部
        assertTrue(keys.containsAll(Set.of("gallery.viewAll", "gallery.viewOwn", "gallery.create",
                "gallery.manageOwn", "gallery.manageAll")));
        // 日记 create/manageOwn
        assertTrue(keys.containsAll(Set.of("diary.create", "diary.manageOwn")));
        assertFalse(keys.contains("diary.viewAll"));
        // 评论 viewAll
        assertTrue(keys.contains("comment.viewAll"));
        assertFalse(keys.contains("comment.moderate"));
        // 文件 upload/view
        assertTrue(keys.containsAll(Set.of("file.upload", "file.view")));
        assertFalse(keys.contains("file.delete"));
        // 受保护键不含
        assertFalse(keys.contains("permission.edit"));
    }

    @Test
    void viewerEmptyAndUnknownRoleEmpty() {
        assertTrue(PermissionKeys.defaultKeysFor("viewer").isEmpty());
        assertTrue(PermissionKeys.defaultKeysFor("guest").isEmpty());
        assertTrue(PermissionKeys.defaultKeysFor(null).isEmpty());
    }

    @Test
    void knownRoles() {
        assertTrue(PermissionKeys.isKnownRole("super_admin"));
        assertTrue(PermissionKeys.isKnownRole("admin"));
        assertTrue(PermissionKeys.isKnownRole("editor"));
        assertTrue(PermissionKeys.isKnownRole("viewer"));
        assertFalse(PermissionKeys.isKnownRole("root"));
        assertFalse(PermissionKeys.isKnownRole(null));
    }

    @Test
    void labelsFallback() {
        assertEquals("查看概览", PermissionKeys.labelOf("overview.view"));
        assertEquals("nonexistent.key", PermissionKeys.labelOf("nonexistent.key"), "未知键回退为键名");
    }
}
