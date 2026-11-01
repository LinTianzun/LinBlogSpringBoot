package com.linblog.module.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.config.AppProperties;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.diary.mapper.DiaryMapper;
import com.linblog.module.stats.entity.ViewLog;
import com.linblog.module.stats.mapper.ViewLogMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.common.HashUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 浏览计数（§7.6）：visitor_key 登录=sha256("u:{id}")、游客=sha256(ip+"|"+ua)（不落明文 IP）；
 * 30 分钟窗口去重后 content.views/diary.views + 1（并发可多记一次，不加锁）。
 */
@Service
public class ViewService {

    public static final String TYPE_CONTENT = "content";
    public static final String TYPE_DIARY = "diary";
    public static final String TYPE_PORTAL = "portal";

    private final ViewLogMapper viewLogMapper;
    private final ContentMapper contentMapper;
    private final DiaryMapper diaryMapper;
    private final AppProperties props;

    public ViewService(ViewLogMapper viewLogMapper, ContentMapper contentMapper,
                       DiaryMapper diaryMapper, AppProperties props) {
        this.viewLogMapper = viewLogMapper;
        this.contentMapper = contentMapper;
        this.diaryMapper = diaryMapper;
        this.props = props;
    }

    /** 记一次浏览；targetType=portal 时 targetId 为空。返回是否实际计数（去重窗口内为 false）。 */
    public boolean report(String targetType, Long targetId, String ip, String ua) {
        if (!List.of(TYPE_CONTENT, TYPE_DIARY, TYPE_PORTAL).contains(targetType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "浏览目标类型不合法");
        }
        boolean isPortal = TYPE_PORTAL.equals(targetType);
        if (!isPortal && targetId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "浏览目标不能为空");
        }
        AuthPrincipal principal = CurrentUser.get();
        String visitorKey = principal != null
                ? HashUtil.sha256Hex("u:" + principal.uid())
                : HashUtil.sha256Hex((ip == null ? "" : ip) + "|" + (ua == null ? "" : ua));
        Long userId = principal == null ? null : principal.uid();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusMinutes(props.viewDedupMinutes());

        LambdaQueryWrapper<ViewLog> dedup = new LambdaQueryWrapper<ViewLog>()
                .eq(ViewLog::getVisitorKey, visitorKey)
                .eq(ViewLog::getTargetType, targetType)
                .ge(ViewLog::getViewedAt, windowStart);
        dedup.eq(!isPortal, ViewLog::getTargetId, targetId);
        if (viewLogMapper.selectCount(dedup) > 0) {
            return false;
        }
        ViewLog log = new ViewLog();
        log.setTargetType(targetType);
        log.setTargetId(isPortal ? null : targetId);
        log.setUserId(userId);
        log.setVisitorKey(visitorKey);
        log.setViewedAt(now);
        viewLogMapper.insert(log);
        if (TYPE_CONTENT.equals(targetType)) {
            contentMapper.incrViews(targetId);
        } else if (TYPE_DIARY.equals(targetType)) {
            diaryMapper.incrViews(targetId);
        }
        return true;
    }
}
