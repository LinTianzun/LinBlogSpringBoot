package com.linblog.module.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.dto.SystemLogView;
import com.linblog.module.audit.entity.SystemLog;
import com.linblog.module.audit.mapper.SystemLogMapper;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 系统日志查询（§5.8 GET /admin/system-logs?module&action&page，systemLog.view）。
 * 全局管理资源：缺权限键 → 40300。
 */
@Service
public class SystemLogQueryService {

    private final SystemLogMapper systemLogMapper;
    private final UserMapper userMapper;

    public SystemLogQueryService(SystemLogMapper systemLogMapper, UserMapper userMapper) {
        this.systemLogMapper = systemLogMapper;
        this.userMapper = userMapper;
    }

    public PageResult<SystemLogView> page(PageQuery query, String module, String action,
                                          AuthPrincipal principal) {
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        if (!principal.has(PermissionKeys.SYSTEM_LOG_VIEW)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问");
        }
        LambdaQueryWrapper<SystemLog> wrapper = new LambdaQueryWrapper<>();
        if (module != null && !module.isBlank()) {
            wrapper.eq(SystemLog::getModule, module);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(SystemLog::getAction, action);
        }
        wrapper.orderByDesc(SystemLog::getId);
        Page<SystemLog> page = new Page<>(query.getPage(), query.getPageSize());
        systemLogMapper.selectPage(page, wrapper);
        List<SystemLog> records = page.getRecords();
        Map<Long, String> names = namesOf(records.stream().map(SystemLog::getUserId).toList());
        List<SystemLogView> views = records.stream().map(l -> new SystemLogView(l.getId(), l.getUserId(),
                l.getUserId() == null ? null : names.get(l.getUserId()), l.getModule(), l.getAction(),
                l.getTargetType(), l.getTargetId(), l.getDetail(), l.getIp(), l.getCreatedAt())).toList();
        return PageResult.of(views, page.getTotal());
    }

    private Map<Long, String> namesOf(List<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, ids)).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }
}
