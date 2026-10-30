package com.linblog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * app.* 配置绑定（环境变量经 application.yml 占位符注入，§4.3）。
 * 启动强校验由 {@link AppStartupGuard} 在任何 Bean 实例化之前完成。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicUrl,
        String allowedOrigins,
        String jwtAccessSecret,
        Duration jwtAccessTtl,
        int refreshTtlDays,
        String cookieDomain,
        boolean cookieSecure,
        String storageDriver,
        String storageLocalDir,
        String s3Endpoint,
        String s3Bucket,
        String s3Ak,
        String s3Sk,
        String thumbWidths,
        DataSize uploadMaxBytes,
        int viewDedupMinutes,
        int auditRetentionDays,
        int trashRetentionDays,
        boolean geoipEnabled,
        int rateLimitLogin,
        int rateLimitReset,
        Smtp smtp
) {

    public record Smtp(String host, int port, boolean secure, String user, String pass, String from) {
    }

    /** ALLOWED_ORIGINS 逗号分隔 → 精确来源列表（trim、去空）。 */
    public List<String> allowedOriginList() {
        return splitList(allowedOrigins);
    }

    /** THUMB_WIDTHS 逗号分隔 → 缩略宽度列表。 */
    public List<Integer> thumbWidthList() {
        return splitList(thumbWidths).stream().map(Integer::parseInt).toList();
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
