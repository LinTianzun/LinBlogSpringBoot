package com.linblog.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.auth.service.AuthService;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.MediaMapper;
import com.linblog.module.media.service.DefaultAvatarService;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端用户服务（§5.3 user.*）：user.view/update/disable/resetPassword/assignAdmin。
 * 超管唯一：二次授予/已存在超管→40900；超管不可禁用/降级→40900。
 * 头像：创建未指定时自动生成默认头像（DefaultAvatarService）；更新可换/可恢复默认。
 */
@Service
public class AdminUserService {

    public record AdminUserView(Long id, String username, String email, String nickname,
                                String role, Integer status, LocalDateTime createdAt,
                                Long avatarMediaId, String avatarUrl) {
        static AdminUserView of(User u, String avatarUrl) {
            return new AdminUserView(u.getId(), u.getUsername(), u.getEmail(), u.getNickname(),
                    u.getRoleCode(), u.getStatus(), u.getCreatedAt(), u.getAvatarMediaId(), avatarUrl);
        }
    }

    private static final Set<String> ASSIGNABLE_ROLES = Set.of("super_admin", "admin", "editor", "viewer");

    private final UserMapper userMapper;
    private final AuthService authService;
    private final SystemLogService systemLogService;
    private final PasswordEncoder passwordEncoder;
    private final MediaMapper mediaMapper;
    private final DefaultAvatarService defaultAvatarService;

    public AdminUserService(UserMapper userMapper, AuthService authService, SystemLogService systemLogService,
                            PasswordEncoder passwordEncoder, MediaMapper mediaMapper,
                            DefaultAvatarService defaultAvatarService) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.systemLogService = systemLogService;
        this.passwordEncoder = passwordEncoder;
        this.mediaMapper = mediaMapper;
        this.defaultAvatarService = defaultAvatarService;
    }

    /** 批量 id→url（列表页防 N+1：selectBatchIds 一次拿全）。 */
    private Map<Long, String> avatarUrlMap(List<User> users) {
        Set<Long> ids = users.stream()
                .map(User::getAvatarMediaId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mediaMapper.selectBatchIds(ids).stream()
                .filter(m -> "active".equals(m.getState()))
                .collect(Collectors.toMap(Media::getId,
                        m -> "/api/v1/media/" + m.getStorageKey(), (a, b) -> a));
    }

    /** 单个头像 URL（无 avatarMediaId 时返回 null）。 */
    private String avatarUrlOf(User u) {
        if (u.getAvatarMediaId() == null) {
            return null;
        }
        return avatarUrlMap(List.of(u)).get(u.getAvatarMediaId());
    }

    /** 校验并解析请求头像：null=不动；0=恢复默认；正数=指定 media。返回可空 mediaId。 */
    private Long resolveRequestedAvatar(Long requested, String username, AuthPrincipal operator) {
        if (requested == null) {
            return null;
        }
        if (requested == 0L) {
            return defaultAvatarService.ensureDefaultAvatar(username, operator.uid()).getId();
        }
        Media media = mediaMapper.selectById(requested);
        if (media == null || !"active".equals(media.getState())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "头像媒体不存在");
        }
        return requested;
    }

    public PageResult<AdminUserView> page(PageQuery query, Integer status) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        String keyword = query.getKeyword();
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            wrapper.and(q -> q.like(User::getUsername, like)
                    .or().like(User::getEmail, like)
                    .or().like(User::getNickname, like));
        }
        if (status != null) {
            wrapper.eq(User::getStatus, status);
        }
        wrapper.orderByDesc(User::getId);
        Page<User> page = userMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        Map<Long, String> urls = avatarUrlMap(page.getRecords());
        List<AdminUserView> views = page.getRecords().stream()
                .map(u -> AdminUserView.of(u,
                        u.getAvatarMediaId() == null ? null : urls.get(u.getAvatarMediaId())))
                .toList();
        return new PageResult<>(views, page.getTotal());
    }

    private User requireUser(long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    @Transactional
    public AdminUserView create(String username, String email, String password, String nickname,
                                Long avatarMediaId, AuthPrincipal operator, String ip) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已被注册");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        user.setRoleCode("viewer");
        user.setStatus(1);
        user.setTokenVersion(0);
        // 头像：指定则校验；未指定则自动生成默认头像（同一用户名确定性复用同一张）
        if (avatarMediaId != null) {
            user.setAvatarMediaId(resolveRequestedAvatar(avatarMediaId, username, operator));
        } else {
            user.setAvatarMediaId(defaultAvatarService
                    .ensureDefaultAvatar(username, operator.uid()).getId());
        }
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "用户名或邮箱已被注册");
        }
        systemLogService.record(operator.uid(), "user", "create", "user", user.getId(), null, ip);
        return AdminUserView.of(user, avatarUrlOf(user));
    }

    @Transactional
    public AdminUserView update(long id, String nickname, String email, Long avatarMediaId,
                                AuthPrincipal operator, String ip) {
        User user = requireUser(id);
        if (email != null && !email.equals(user.getEmail())
                && userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已被注册");
        }
        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (email != null) {
            user.setEmail(email);
        }
        // 头像：null=不动；0=恢复默认；正数=换为指定 media
        Long resolvedAvatar = resolveRequestedAvatar(avatarMediaId, user.getUsername(), operator);
        if (resolvedAvatar != null) {
            user.setAvatarMediaId(resolvedAvatar);
        }
        userMapper.updateById(user);
        systemLogService.record(operator.uid(), "user", "update", "user", id, null, ip);
        return AdminUserView.of(user, avatarUrlOf(user));
    }

    /** status：1 启用 / 0 禁用；禁用同时吊销令牌并递增 token_version。 */
    @Transactional
    public AdminUserView updateStatus(long id, int status, AuthPrincipal operator, String ip) {
        User user = requireUser(id);
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 仅允许 0 或 1");
        }
        if (status == 0 && "super_admin".equals(user.getRoleCode())) {
            throw new BizException(ErrorCode.CONFLICT, "超级管理员不可禁用");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        if (status == 0) {
            authService.invalidateUserAuth(id);
        }
        systemLogService.record(operator.uid(), "user", status == 0 ? "disable" : "enable",
                "user", id, null, ip);
        return AdminUserView.of(user, avatarUrlOf(user));
    }

    /** 重置密码：新 bcrypt 哈希 + tv+1 + 吊销全部刷新令牌。 */
    @Transactional
    public void resetPassword(long id, String newPassword, AuthPrincipal operator, String ip) {
        User user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userMapper.updateById(user);
        authService.invalidateUserAuth(id);
        systemLogService.record(operator.uid(), "user", "reset_password", "user", id, null, ip);
    }

    /** 改角色：仅超管；目标为超管时不可降级；授予 super_admin 全库唯一。 */
    @Transactional
    public AdminUserView updateRole(long id, String role, AuthPrincipal operator, String ip) {
        User user = requireUser(id);
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未知角色: " + role);
        }
        if ("super_admin".equals(user.getRoleCode()) && !"super_admin".equals(role)) {
            throw new BizException(ErrorCode.CONFLICT, "超级管理员不可降级");
        }
        if ("super_admin".equals(role) && !"super_admin".equals(user.getRoleCode())) {
            Long existing = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getRoleCode, "super_admin")
                    .ne(User::getId, id));
            if (existing != null && existing > 0) {
                throw new BizException(ErrorCode.CONFLICT, "已存在超级管理员");
            }
        }
        user.setRoleCode(role);
        try {
            userMapper.updateById(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "已存在超级管理员");
        }
        authService.invalidateUserAuth(id);
        systemLogService.record(operator.uid(), "user", "role_change", "user", id, role, ip);
        return AdminUserView.of(user, avatarUrlOf(user));
    }
}
