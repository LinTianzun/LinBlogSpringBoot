package com.linblog.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.HashUtil;
import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.auth.entity.PasswordResetToken;
import com.linblog.module.auth.mapper.PasswordResetTokenMapper;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.JwtService;
import com.linblog.security.UserAuthStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 认证服务（§5.1/§7.2）：注册/登录/刷新/登出/me/密码三端点。
 * 登录失败统一 40100「账号或密码不正确」；禁用账号 40300。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final UserMapper userMapper;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetTokenMapper resetTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserAuthStateService authStateService;
    private final SystemLogService systemLogService;
    private final AppProperties props;

    public AuthService(UserMapper userMapper, RefreshTokenService refreshTokenService,
                       PasswordResetTokenMapper resetTokenMapper, PasswordEncoder passwordEncoder,
                       JwtService jwtService, UserAuthStateService authStateService,
                       SystemLogService systemLogService, AppProperties props) {
        this.userMapper = userMapper;
        this.refreshTokenService = refreshTokenService;
        this.resetTokenMapper = resetTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authStateService = authStateService;
        this.systemLogService = systemLogService;
        this.props = props;
    }

    public record UserView(Long id, String username, String nickname, String email, String role,
                           Integer status, LocalDateTime createdAt) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getNickname(), u.getEmail(),
                    u.getRoleCode(), u.getStatus(), u.getCreatedAt());
        }
    }

    public record LoginResult(long uid, String accessToken, String refreshToken, UserView user) {
    }

    public record MeResult(UserView user, java.util.Set<String> permissions, boolean isSuperAdmin) {
    }

    /** 注册：并发重名/重邮箱统一 40900（预检给出更具体文案）。 */
    @Transactional
    public UserView register(String username, String email, String password, String ip) {
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
        user.setNickname(username);
        user.setRoleCode("viewer");
        user.setStatus(1);
        user.setTokenVersion(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "用户名或邮箱已被注册");
        }
        systemLogService.record(user.getId(), "auth", "register", "user", String.valueOf(user.getId()), null, ip);
        return UserView.of(user);
    }

    /** 登录：account 支持用户名或邮箱；成功返回双令牌（Cookie 由控制器下发）。 */
    public LoginResult login(String account, String password, String ip, String ua) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, account)
                .or()
                .eq(User::getEmail, account)
                .last("LIMIT 1"));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "账号或密码不正确");
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        RefreshTokenService.Issued issued = refreshTokenService.create(user.getId(), ip, ua);
        String access = jwtService.issue(user.getId(), user.getTokenVersion(), user.getRoleCode());
        return new LoginResult(user.getId(), access, issued.rawToken(), UserView.of(user));
    }

    /** 刷新：轮换 + 复用检测在 RefreshTokenService；刷新后按最新 tv/role 签访问令牌。 */
    public LoginResult refresh(String rawRefresh, String ip, String ua) {
        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(rawRefresh, ip, ua);
        User user = userMapper.selectById(rotated.userId());
        if (user == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        String access = jwtService.issue(user.getId(), user.getTokenVersion(), user.getRoleCode());
        return new LoginResult(user.getId(), access, rotated.rawToken(), UserView.of(user));
    }

    /** 登出：吊销令牌所属 family；无令牌也清 Cookie。 */
    public void logout(String rawRefresh) {
        refreshTokenService.revokeFamily(refreshTokenService.familyOf(rawRefresh));
    }

    /** me：库内最新资料 + 权限键 + 超管标记。 */
    public MeResult me(AuthPrincipal principal) {
        User user = userMapper.selectById(principal.uid());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return new MeResult(UserView.of(user), principal.permissions(), principal.superAdmin());
    }

    /** 重置申请：恒 ok；不暴露邮箱是否存在；SMTP 未配置时把链接打到日志。 */
    public void resetRequest(String email, String ip) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)
                .last("LIMIT 1"));
        if (user == null) {
            return;
        }
        resetTokenMapper.invalidateAllForUser(user.getId(), LocalDateTime.now());
        String raw = HashUtil.randomToken();
        PasswordResetToken row = new PasswordResetToken();
        row.setUserId(user.getId());
        row.setTokenHash(HashUtil.sha256Hex(raw));
        row.setExpiresAt(LocalDateTime.now().plus(RESET_TOKEN_TTL));
        resetTokenMapper.insert(row);
        String link = props.publicUrl() + "/reset-password?token=" + raw;
        if (props.smtp() != null && props.smtp().host() != null && !props.smtp().host().isBlank()) {
            log.info("[reset-request] ip={} 重置邮件待发送至 {}（SMTP 投递在后续里程碑接入），链接: {}", ip, email, link);
        } else {
            log.info("[reset-request] SMTP 未配置，密码重置链接: {}", link);
        }
    }

    /** 重置：一次性令牌；成功后 token_version+1 + 吊销全部刷新令牌。 */
    @Transactional
    public void reset(String rawToken, String newPassword, String ip) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "重置链接无效或已过期");
        }
        LocalDateTime now = LocalDateTime.now();
        PasswordResetToken row = resetTokenMapper.selectOne(new LambdaQueryWrapper<PasswordResetToken>()
                .eq(PasswordResetToken::getTokenHash, HashUtil.sha256Hex(rawToken)));
        if (row == null || row.getUsedAt() != null || row.getExpiresAt().isBefore(now)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "重置链接无效或已过期");
        }
        User user = userMapper.selectById(row.getUserId());
        if (user == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "重置链接无效或已过期");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userMapper.updateById(user);
        if (resetTokenMapper.markUsed(row.getId(), now) == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "重置链接无效或已过期");
        }
        refreshTokenService.revokeAllForUser(user.getId());
        authStateService.evict(user.getId());
        systemLogService.record(user.getId(), "auth", "password_reset", "user", String.valueOf(user.getId()), null, ip);
    }

    /** 改密：验证旧密码；成功后 token_version+1 + 吊销全部刷新令牌（需重新登录）。 */
    @Transactional
    public void change(AuthPrincipal principal, String oldPassword, String newPassword, String ip) {
        User user = userMapper.selectById(principal.uid());
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "旧密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userMapper.updateById(user);
        refreshTokenService.revokeAllForUser(user.getId());
        authStateService.evict(user.getId());
        systemLogService.record(user.getId(), "auth", "password_change", "user", String.valueOf(user.getId()), null, ip);
    }

    /** 供 admin 模块禁用/改密/改角色后同步：吊销令牌 + 失效认证缓存。 */
    public void invalidateUserAuth(long userId) {
        refreshTokenService.revokeAllForUser(userId);
        authStateService.evict(userId);
    }

    public Map<String, Object> noopPlaceholder() {
        return Map.of();
    }
}
