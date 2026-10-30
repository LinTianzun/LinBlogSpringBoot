package com.linblog.module.audit.service;

import com.linblog.module.audit.entity.SystemLog;
import com.linblog.module.audit.mapper.SystemLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 系统日志（system_logs：module/action/target/detail/ip）。
 * 审计失败只告警，不影响业务事务。
 */
@Service
public class SystemLogService {

    private static final Logger log = LoggerFactory.getLogger(SystemLogService.class);

    private final SystemLogMapper systemLogMapper;

    public SystemLogService(SystemLogMapper systemLogMapper) {
        this.systemLogMapper = systemLogMapper;
    }

    public void record(Long userId, String module, String action, String targetType, Long targetId,
                       String detail, String ip) {
        try {
            SystemLog entry = new SystemLog();
            entry.setUserId(userId);
            entry.setModule(module);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), 500)));
            entry.setIp(ip);
            systemLogMapper.insert(entry);
        } catch (RuntimeException e) {
            log.warn("system_log 写入失败 module={} action={}: {}", module, action, e.getMessage());
        }
    }
}
