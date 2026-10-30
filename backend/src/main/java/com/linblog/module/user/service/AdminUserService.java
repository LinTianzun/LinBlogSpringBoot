package com.linblog.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.auth.service.AuthService;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 管理端用户服务（§5.3 user.*）：user.view/update/disable/resetPassword/assignAdmin。
 * 超管唯一：二次授予/已存在超管→40900；超管不可禁用/降级→40900。
 */
@Service
public class AdminUserService {

    public record AdminUserView(Long id, String username, String email, String nickname,
                                String role, Integer status, LocalDateTime createdAt) {
        static AdminUserView of(User u) {
            return new AdminUserView(u.getId(), u.getUsername(), u.getEmail(), u.getNickname(),
                    u.getRoleCode(), u.getStatus(), u.getCreatedAt());
        }
    }

    private static final Set<String> ASSIGNABLE_ROLES = Set.of("super_admin", "admin", "editor", "viewer");

    private final UserMapper userMapper;
    private final AuthService authService;
    private final SystemLogService systemLogService;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(UserMapper userMapper, AuthService authService, SystemLogService systemLogService,
                            PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.authService = authService;
        this.systemLogService = systemLogService;
        this.passwordEncoder = passwordEncoder;
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
        return new PageResult<>(page.getRecords().stream().map(AdminUserView::of).toList(), page.getTotal());
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
                                AuthPrincipal operator, String ip) {
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
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "用户名或邮箱已被注册");
        }
        systemLogService.record(operator.uid(), "user", "create", "user", String.valueOf(user.getId()), null, ip);
        return AdminUserView.of(user);
    }

    @Transactional
    public AdminUserView update(long id, String nickname, String email, AuthPrincipal operator, String ip) {
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
        userMapper.updateById(user);
        systemLogService.record(operator.uid(), "user", "update", "user", String.valueOf(id), null, ip);
        return AdminUserView.of(user);
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
                "user", String.valueOf(id), null, ip);
        return AdminUserView.of(user);
    }

    /** 重置密码：新 bcrypt 哈希 + tv+1 + 吊销全部刷新令牌。 */
    @Transactional
    public void resetPassword(long id, String newPassword, AuthPrincipal operator, String ip) {
        User user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userMapper.updateById(user);
        authService.invalidateUserAuth(id);
        systemLogService.record(operator.uid(), "user", "reset_password", "user", String.valueOf(id), null, ip);
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
        systemLogService.record(operator.uid(), "user", "role_change", "user", String.valueOf(id), role, ip);
        return AdminUserView.of(user);
    }
}
