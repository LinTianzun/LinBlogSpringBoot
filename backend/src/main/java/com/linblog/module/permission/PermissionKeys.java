package com.linblog.module.permission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 权限清单（§5.2）：8 组 33 键，`*`=受保护（permission.edit / user.assignAdmin）。
 * 默认矩阵：super_admin=全部；admin=除受保护外全部；editor=15 键；viewer=空。
 * super_admin 键集代码硬编码，不落 role_permissions 表。
 */
public final class PermissionKeys {

    // —— 角色 ——
    public static final String SUPER_ADMIN = "super_admin";
    public static final String ADMIN = "admin";
    public static final String EDITOR = "editor";
    public static final String VIEWER = "viewer";
    /** 矩阵可配置的角色（super_admin 行拒绝修改）。 */
    public static final Set<String> CONFIGURABLE_ROLES = Set.of(ADMIN, EDITOR, VIEWER);

    // —— 概览 ——
    public static final String OVERVIEW_VIEW = "overview.view";
    // —— 用户 ——
    public static final String USER_VIEW = "user.view";
    public static final String USER_UPDATE = "user.update";
    public static final String USER_DISABLE = "user.disable";
    public static final String USER_RESET_PASSWORD = "user.resetPassword";
    public static final String USER_ASSIGN_ADMIN = "user.assignAdmin";
    // —— 权限 ——
    public static final String PERMISSION_VIEW = "permission.view";
    public static final String PERMISSION_EDIT = "permission.edit";
    // —— 内容：文章 ——
    public static final String ARTICLE_VIEW_ALL = "article.viewAll";
    public static final String ARTICLE_VIEW_OWN = "article.viewOwn";
    public static final String ARTICLE_CREATE = "article.create";
    public static final String ARTICLE_MANAGE_OWN = "article.manageOwn";
    public static final String ARTICLE_MANAGE_ALL = "article.manageAll";
    public static final String ARTICLE_PUBLISH = "article.publish";
    // —— 内容：图库 ——
    public static final String GALLERY_VIEW_ALL = "gallery.viewAll";
    public static final String GALLERY_VIEW_OWN = "gallery.viewOwn";
    public static final String GALLERY_CREATE = "gallery.create";
    public static final String GALLERY_MANAGE_OWN = "gallery.manageOwn";
    public static final String GALLERY_MANAGE_ALL = "gallery.manageAll";
    // —— 日记 ——
    public static final String DIARY_VIEW_ALL = "diary.viewAll";
    public static final String DIARY_CREATE = "diary.create";
    public static final String DIARY_MANAGE_OWN = "diary.manageOwn";
    public static final String DIARY_MANAGE_ALL = "diary.manageAll";
    // —— 评论 ——
    public static final String COMMENT_VIEW_ALL = "comment.viewAll";
    public static final String COMMENT_MODERATE = "comment.moderate";
    // —— 私信 ——
    public static final String MESSAGE_PRIVATE = "message.private";
    public static final String MESSAGE_WALL_MODERATE = "message.wallModerate";
    // —— 文件系统与系统 ——
    public static final String FILE_VIEW = "file.view";
    public static final String FILE_UPLOAD = "file.upload";
    public static final String FILE_DELETE = "file.delete";
    public static final String SYSTEM_LOG_VIEW = "systemLog.view";
    public static final String SYSTEM_SETTING = "system.setting";
    public static final String LABEL_MANAGE = "label.manage";

    /** 受保护键：矩阵编辑拒绝修改，默认仅 super_admin 持有。 */
    public static final Set<String> PROTECTED_KEYS = Set.of(PERMISSION_EDIT, USER_ASSIGN_ADMIN);

    /** 8 组分组目录（顺序即展示顺序）。 */
    public static final Map<String, List<String>> GROUPS = buildGroups();

    /** 全部 33 键（组顺序）。 */
    public static final Set<String> ALL_KEYS = buildAllKeys();

    private PermissionKeys() {
    }

    private static Map<String, List<String>> buildGroups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("概览", List.of(OVERVIEW_VIEW));
        groups.put("用户", List.of(USER_VIEW, USER_UPDATE, USER_DISABLE, USER_RESET_PASSWORD, USER_ASSIGN_ADMIN));
        groups.put("权限", List.of(PERMISSION_VIEW, PERMISSION_EDIT));
        groups.put("内容", List.of(ARTICLE_VIEW_ALL, ARTICLE_VIEW_OWN, ARTICLE_CREATE, ARTICLE_MANAGE_OWN,
                ARTICLE_MANAGE_ALL, ARTICLE_PUBLISH, GALLERY_VIEW_ALL, GALLERY_VIEW_OWN, GALLERY_CREATE,
                GALLERY_MANAGE_OWN, GALLERY_MANAGE_ALL));
        groups.put("日记", List.of(DIARY_VIEW_ALL, DIARY_CREATE, DIARY_MANAGE_OWN, DIARY_MANAGE_ALL));
        groups.put("评论", List.of(COMMENT_VIEW_ALL, COMMENT_MODERATE));
        groups.put("私信", List.of(MESSAGE_PRIVATE, MESSAGE_WALL_MODERATE));
        groups.put("文件系统", List.of(FILE_VIEW, FILE_UPLOAD, FILE_DELETE, SYSTEM_LOG_VIEW, SYSTEM_SETTING, LABEL_MANAGE));
        return groups;
    }

    private static Set<String> buildAllKeys() {
        Set<String> keys = new LinkedHashSet<>();
        GROUPS.values().forEach(keys::addAll);
        return Set.copyOf(keys);
    }

    /** 默认矩阵（§5.2）：role → 键集合。 */
    public static Set<String> defaultKeysFor(String roleCode) {
        return switch (roleCode == null ? "" : roleCode) {
            case SUPER_ADMIN -> ALL_KEYS;
            case ADMIN -> allExceptProtected();
            case EDITOR -> Set.of(
                    ARTICLE_VIEW_ALL, ARTICLE_VIEW_OWN, ARTICLE_CREATE, ARTICLE_MANAGE_OWN, ARTICLE_MANAGE_ALL,
                    GALLERY_VIEW_ALL, GALLERY_VIEW_OWN, GALLERY_CREATE, GALLERY_MANAGE_OWN, GALLERY_MANAGE_ALL,
                    DIARY_CREATE, DIARY_MANAGE_OWN, COMMENT_VIEW_ALL, FILE_UPLOAD, FILE_VIEW);
            default -> Set.of();
        };
    }

    public static Set<String> allExceptProtected() {
        Set<String> keys = new LinkedHashSet<>(ALL_KEYS);
        keys.removeAll(PROTECTED_KEYS);
        return Set.copyOf(keys);
    }

    public static boolean isKnownRole(String roleCode) {
        return Set.of(SUPER_ADMIN, ADMIN, EDITOR, VIEWER).contains(roleCode);
    }

    /** 目录项：key + 中文标签 + 受保护标记。 */
    public record Item(String key, String label, boolean isProtected) {
    }

    public record Group(String name, List<Item> items) {
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(OVERVIEW_VIEW, "查看概览"),
            Map.entry(USER_VIEW, "查看用户"),
            Map.entry(USER_UPDATE, "编辑用户"),
            Map.entry(USER_DISABLE, "禁用用户"),
            Map.entry(USER_RESET_PASSWORD, "重置用户密码"),
            Map.entry(USER_ASSIGN_ADMIN, "授予管理角色"),
            Map.entry(PERMISSION_VIEW, "查看权限"),
            Map.entry(PERMISSION_EDIT, "编辑权限矩阵"),
            Map.entry(ARTICLE_VIEW_ALL, "查看全部文章"),
            Map.entry(ARTICLE_VIEW_OWN, "查看我的文章"),
            Map.entry(ARTICLE_CREATE, "创建文章"),
            Map.entry(ARTICLE_MANAGE_OWN, "管理我的文章"),
            Map.entry(ARTICLE_MANAGE_ALL, "管理全部文章"),
            Map.entry(ARTICLE_PUBLISH, "发布文章"),
            Map.entry(GALLERY_VIEW_ALL, "查看全部图库"),
            Map.entry(GALLERY_VIEW_OWN, "查看我的图库"),
            Map.entry(GALLERY_CREATE, "创建图库"),
            Map.entry(GALLERY_MANAGE_OWN, "管理我的图库"),
            Map.entry(GALLERY_MANAGE_ALL, "管理全部图库"),
            Map.entry(DIARY_VIEW_ALL, "查看全部日记"),
            Map.entry(DIARY_CREATE, "创建日记"),
            Map.entry(DIARY_MANAGE_OWN, "管理我的日记"),
            Map.entry(DIARY_MANAGE_ALL, "管理全部日记"),
            Map.entry(COMMENT_VIEW_ALL, "查看全部评论"),
            Map.entry(COMMENT_MODERATE, "评论审核"),
            Map.entry(MESSAGE_PRIVATE, "私信"),
            Map.entry(MESSAGE_WALL_MODERATE, "留言墙管理"),
            Map.entry(FILE_VIEW, "查看文件"),
            Map.entry(FILE_UPLOAD, "上传文件"),
            Map.entry(FILE_DELETE, "删除文件"),
            Map.entry(SYSTEM_LOG_VIEW, "查看系统日志"),
            Map.entry(SYSTEM_SETTING, "系统设置"),
            Map.entry(LABEL_MANAGE, "标签管理"));

    public static String labelOf(String key) {
        return LABELS.getOrDefault(key, key);
    }
}
