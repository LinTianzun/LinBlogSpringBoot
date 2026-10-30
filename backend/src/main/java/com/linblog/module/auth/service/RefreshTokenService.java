package com.linblog.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.HashUtil;
import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.auth.entity.RefreshToken;
import com.linblog.module.auth.mapper.RefreshTokenMapper;
import com.linblog.module.user.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 刷新令牌服务（§3.1/§7.2）：登录建 family_id（UUID），仅存 SHA-256(token_hash)；
 * 刷新时轮换（revoke 旧 + rotated_to_id）；已吊销令牌被再次提交 ⇒ 重放：
 * 吊销全 family + token_version+1 + 系统日志 + 40102。
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenMapper refreshTokenMapper;
    private final UserMapper userMapper;
    private final SystemLogService systemLogService;
    private final AppProperties props;

    public RefreshTokenService(RefreshTokenMapper refreshTokenMapper, UserMapper userMapper,
                               SystemLogService systemLogService, AppProperties props) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.userMapper = userMapper;
        this.systemLogService = systemLogService;
        this.props = props;
    }

    public record Issued(String rawToken, String familyId) {
    }

    public record Rotated(long userId, String rawToken) {
    }

    /** 登录/注册后签发新 family 的首枚刷新令牌。 */
    @Transactional
    public Issued create(long userId, String ip, String ua) {
        String raw = HashUtil.randomToken();
        RefreshToken row = new RefreshToken();
        row.setUserId(userId);
        row.setFamilyId(UUID.randomUUID().toString());
        row.setTokenHash(HashUtil.sha256Hex(raw));
        row.setExpiresAt(LocalDateTime.now().plusDays(props.refreshTtlDays()));
        row.setCreatedIp(truncate(ip, 45));
        row.setCreatedUaHash(HashUtil.sha256Hex(ua == null ? "" : ua).substring(0, 32));
        refreshTokenMapper.insert(row);
        return new Issued(raw, row.getFamilyId());
    }

    /**
     * 轮换：校验 presented 原文令牌 → 已吊销 ⇒ 重放处置 → 过期 ⇒ 40102（不写任何行）→
     * 同 family 发新令牌并 revoke 旧。并发 markRotated 失败也按重放处置。
     */
    @Transactional
    public Rotated rotate(String presentedRaw, String ip, String ua) {
        if (presentedRaw == null || presentedRaw.isBlank()) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        String presentedHash = HashUtil.sha256Hex(presentedRaw);
        RefreshToken current = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, presentedHash));
        if (current == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        if (current.getRevokedAt() != null) {
            handleReplay(current, now, ip);
        }
        if (current.getExpiresAt().isBefore(now)) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }

        String raw = HashUtil.randomToken();
        RefreshToken next = new RefreshToken();
        next.setUserId(current.getUserId());
        next.setFamilyId(current.getFamilyId());
        next.setTokenHash(HashUtil.sha256Hex(raw));
        next.setExpiresAt(now.plusDays(props.refreshTtlDays()));
        next.setCreatedIp(truncate(ip, 45));
        next.setCreatedUaHash(HashUtil.sha256Hex(ua == null ? "" : ua).substring(0, 32));
        refreshTokenMapper.insert(next);

        int updated = refreshTokenMapper.markRotated(current.getId(), next.getId(), now);
        if (updated == 0) {
            handleReplay(current, now, ip);
        }
        return new Rotated(current.getUserId(), raw);
    }

    private void handleReplay(RefreshToken current, LocalDateTime now, String ip) {
        refreshTokenMapper.revokeFamily(current.getFamilyId(), now);
        userMapper.bumpTokenVersion(current.getUserId());
        systemLogService.record(current.getUserId(), "auth", "refresh_replay", "user",
                String.valueOf(current.getUserId()), "刷新令牌重放：已吊销 family 并递增 token_version", ip);
        throw new BizException(ErrorCode.REFRESH_INVALID);
    }

    /** 找到令牌所属 family（logout 用），不存在返回 null。 */
    public String familyOf(String presentedRaw) {
        if (presentedRaw == null || presentedRaw.isBlank()) {
            return null;
        }
        RefreshToken row = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, HashUtil.sha256Hex(presentedRaw)));
        return row == null ? null : row.getFamilyId();
    }

    @Transactional
    public void revokeFamily(String familyId) {
        if (familyId != null) {
            refreshTokenMapper.revokeFamily(familyId, LocalDateTime.now());
        }
    }

    @Transactional
    public void revokeAllForUser(long userId) {
        refreshTokenMapper.revokeAllForUser(userId, LocalDateTime.now());
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(s.length(), max));
    }
}
