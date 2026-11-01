package com.linblog.module.setting.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.setting.entity.SiteSetting;
import com.linblog.module.setting.mapper.SiteSettingMapper;
import com.linblog.security.AuthPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 系统设置（§5.2，M4）：白名单键值存储，GET 返回全量键（未设置为空串），
 * PUT 仅接受白名单键并逐键限长；updated_at 由库 ON UPDATE 维护。
 */
@Service
public class SettingService {

    /** 白名单键 → 值长度上限（§6.1 settings 页签字段）。 */
    private static final Map<String, Integer> LIMITS = buildLimits();

    private static Map<String, Integer> buildLimits() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("site_title", 50);
        limits.put("site_description", 200);
        limits.put("site_keywords", 200);
        limits.put("icp", 50);
        limits.put("footer_text", 200);
        limits.put("announcement", 500);
        return Collections.unmodifiableMap(limits);
    }

    private final SiteSettingMapper siteSettingMapper;
    private final SystemLogService systemLogService;

    public SettingService(SiteSettingMapper siteSettingMapper, SystemLogService systemLogService) {
        this.siteSettingMapper = siteSettingMapper;
        this.systemLogService = systemLogService;
    }

    /** GET /admin/settings（system.setting）：全部白名单键，值为空串表示未设置。 */
    public Map<String, String> all() {
        Map<String, String> stored = new HashMap<>();
        for (SiteSetting row : siteSettingMapper.selectList(null)) {
            stored.put(row.getSettingKey(), row.getSettingValue());
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : LIMITS.keySet()) {
            result.put(key, stored.getOrDefault(key, ""));
        }
        return result;
    }

    /** PUT /admin/settings（system.setting）：全量校验通过后逐键 UPSERT，审计一次。 */
    @Transactional
    public Set<String> update(AuthPrincipal operator, Map<String, String> values, String ip) {
        if (values == null || values.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "settings 不能为空");
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            Integer limit = LIMITS.get(key);
            if (limit == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知的设置项: " + key);
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (value.length() > limit) {
                throw new BizException(ErrorCode.BAD_REQUEST, "设置项 " + key + " 超过长度上限 " + limit);
            }
            sanitized.put(key, value);
        }
        for (Map.Entry<String, String> entry : sanitized.entrySet()) {
            upsert(entry.getKey(), entry.getValue(), operator.uid());
        }
        systemLogService.record(operator.uid(), "setting", "update", "setting", null,
                String.join(",", sanitized.keySet()), ip);
        return sanitized.keySet();
    }

    private void upsert(String key, String value, long uid) {
        SiteSetting row = siteSettingMapper.selectById(key);
        if (row == null) {
            SiteSetting created = new SiteSetting();
            created.setSettingKey(key);
            created.setSettingValue(value);
            created.setUpdatedBy(uid);
            try {
                siteSettingMapper.insert(created);
                return;
            } catch (DuplicateKeyException e) {
                row = siteSettingMapper.selectById(key);
            }
        }
        if (row == null) {
            throw new BizException(ErrorCode.INTERNAL, "设置项写入失败");
        }
        siteSettingMapper.update(null, new LambdaUpdateWrapper<SiteSetting>()
                .eq(SiteSetting::getSettingKey, key)
                .set(SiteSetting::getSettingValue, value)
                .set(SiteSetting::getUpdatedBy, uid));
    }
}
