package com.linblog.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动强校验（§4.3，继承原项目 config/default.js 哲学：配置错误 fail-fast）。
 * 以 BeanFactoryPostProcessor 身份在任何业务 Bean / DataSource / Flyway 之前执行，
 * 所有配置问题一次性聚合报告，拒绝启动。
 */
@Component
public class AppStartupGuard implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AppStartupGuard.class);

    private final Environment environment;

    public AppStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        AppProperties props = Binder.get(environment)
                .bind("app", Bindable.of(AppProperties.class))
                .orElse(new AppProperties(null, null, null, null, 0, null, false, null, null,
                        null, null, null, null, null, null, 0, 0, 0, false, 0, 0, null));

        List<String> errors = new ArrayList<>();

        // —— 数据库（DB_URL / DB_USERNAME / DB_PASSWORD）——
        requireEnv(errors, "spring.datasource.url", "DB_URL");
        requireEnv(errors, "spring.datasource.username", "DB_USERNAME");
        requireEnv(errors, "spring.datasource.password", "DB_PASSWORD");

        // —— JWT ——
        String secret = props.jwtAccessSecret();
        if (isBlank(secret)) {
            errors.add("JWT_ACCESS_SECRET 未配置（生产请用 openssl rand -hex 32 生成）");
        } else if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            errors.add("JWT_ACCESS_SECRET 强度不足：需 ≥32 字符，当前 " + secret.length() + " 字符");
        }
        if (props.jwtAccessTtl() == null || props.jwtAccessTtl().isNegative() || props.jwtAccessTtl().isZero()) {
            errors.add("JWT_ACCESS_TTL 必须为正时长（ISO-8601，如 PT15M）");
        }
        if (props.refreshTtlDays() < 1) {
            errors.add("REFRESH_TTL_DAYS 必须 ≥ 1");
        }

        // —— ALLOWED_ORIGINS ——
        List<String> origins = props.allowedOriginList();
        if (origins.isEmpty()) {
            errors.add("ALLOWED_ORIGINS 未配置（逗号分隔的精确来源，如 http://localhost:5173）");
        } else {
            for (String origin : origins) {
                String problem = originProblem(origin);
                if (problem != null) {
                    errors.add("ALLOWED_ORIGINS 含非法来源 \"" + origin + "\": " + problem);
                }
            }
        }

        // —— 数值下限 ——
        if (props.viewDedupMinutes() < 1) {
            errors.add("VIEW_DEDUP_MINUTES 必须 ≥ 1");
        }
        if (props.auditRetentionDays() < 1) {
            errors.add("AUDIT_RETENTION_DAYS 必须 ≥ 1");
        }
        if (props.trashRetentionDays() < 1) {
            errors.add("TRASH_RETENTION_DAYS 必须 ≥ 1");
        }
        if (props.rateLimitLogin() < 1) {
            errors.add("RATE_LIMIT_LOGIN 必须 ≥ 1");
        }
        if (props.rateLimitReset() < 1) {
            errors.add("RATE_LIMIT_RESET 必须 ≥ 1");
        }
        if (props.uploadMaxBytes() == null || props.uploadMaxBytes().toBytes() <= 0) {
            errors.add("UPLOAD_MAX_BYTES 必须为正（如 20MB）");
        }

        // —— 存储驱动 ——
        String driver = props.storageDriver();
        if (!"local".equals(driver) && !"s3".equals(driver)) {
            errors.add("STORAGE_DRIVER 仅支持 local / s3，当前: " + driver);
        } else if ("s3".equals(driver)) {
            if (isBlank(props.s3Endpoint()) || isBlank(props.s3Bucket())
                    || isBlank(props.s3Ak()) || isBlank(props.s3Sk())) {
                errors.add("STORAGE_DRIVER=s3 时 S3_ENDPOINT / S3_BUCKET / S3_AK / S3_SK 必填");
            }
        } else if (isBlank(props.storageLocalDir())) {
            errors.add("STORAGE_DRIVER=local 时 STORAGE_LOCAL_DIR 必填");
        }

        // —— 缩略宽度 ——
        try {
            List<Integer> widths = props.thumbWidthList();
            if (widths.isEmpty() || widths.stream().anyMatch(w -> w < 16 || w > 4096)) {
                errors.add("THUMB_WIDTHS 必须为 16–4096 的逗号分隔整数（如 320,640,1280）");
            }
        } catch (NumberFormatException e) {
            errors.add("THUMB_WIDTHS 格式非法：必须为逗号分隔整数（当前: " + props.thumbWidths() + "）");
        }

        // —— SMTP ——
        AppProperties.Smtp smtp = props.smtp();
        if (smtp != null && !isBlank(smtp.host()) && isBlank(smtp.from())) {
            errors.add("配置了 SMTP_HOST 则 SMTP_FROM 必填");
        }

        if (!errors.isEmpty()) {
            String message = "启动配置校验失败，拒绝启动（" + errors.size() + " 项）：\n  - "
                    + String.join("\n  - ", errors);
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("启动配置校验通过：{} 个 CORS 来源，storage={}，accessTtl={}",
                origins.size(), driver, props.jwtAccessTtl());
    }

    private void requireEnv(List<String> errors, String propertyKey, String envName) {
        if (isBlank(environment.getProperty(propertyKey))) {
            errors.add(envName + " 未配置（数据库连接必填）");
        }
    }

    /** 返回 null 表示合法；否则返回原因。要求 http/https 绝对地址且无路径/查询。 */
    private static String originProblem(String origin) {
        try {
            URI uri = URI.create(origin);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                return "协议必须是 http/https";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "缺少主机名";
            }
            if ((uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                    || uri.getQuery() != null || uri.getFragment() != null) {
                return "来源只能是 scheme://host[:port]，不允许路径/查询/锚点";
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "不是合法 URL";
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
