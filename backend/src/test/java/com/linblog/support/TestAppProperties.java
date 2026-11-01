package com.linblog.support;

import com.linblog.config.AppProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 测试用 AppProperties 工厂：record 22 参构造太长，统一从这里取。
 */
public final class TestAppProperties {

    private TestAppProperties() {
    }

    public static AppProperties defaults() {
        return of(null);
    }

    /** overrides[i] 覆盖第 i 个参数（null 跳过），顺序同 record 声明。 */
    public static AppProperties of(Object[] overrides) {
        Object[] v = new Object[]{
                "http://localhost:5173",            // publicUrl
                "http://localhost:5173",            // allowedOrigins
                "0123456789abcdef0123456789abcdef", // jwtAccessSecret (32B)
                Duration.ofMinutes(15),             // jwtAccessTtl
                7,                                  // refreshTtlDays
                null,                               // cookieDomain
                false,                              // cookieSecure
                "local",                            // storageDriver
                "./data/storage",                   // storageLocalDir
                null,                               // s3Endpoint
                null,                               // s3Bucket
                null,                               // s3Ak
                null,                               // s3Sk
                "320,640,1280",                     // thumbWidths
                DataSize.ofMegabytes(20),           // uploadMaxBytes
                30,                                 // viewDedupMinutes
                90,                                 // auditRetentionDays
                30,                                 // trashRetentionDays
                false,                              // geoipEnabled
                2,                                  // rateLimitLogin
                3,                                  // rateLimitReset
                null                                // smtp
        };
        if (overrides != null) {
            // overrides 为 (下标, 值) 成对序列：按 record 参数序覆盖指定位置。
            for (int i = 0; i + 1 < overrides.length; i += 2) {
                int idx = (Integer) overrides[i];
                if (idx >= 0 && idx < v.length && overrides[i + 1] != null) {
                    v[idx] = overrides[i + 1];
                }
            }
        }
        return new AppProperties((String) v[0], (String) v[1], (String) v[2], (Duration) v[3],
                (Integer) v[4], (String) v[5], (Boolean) v[6], (String) v[7], (String) v[8],
                (String) v[9], (String) v[10], (String) v[11], (String) v[12], (String) v[13],
                (DataSize) v[14], (Integer) v[15], (Integer) v[16], (Integer) v[17], (Boolean) v[18],
                (Integer) v[19], (Integer) v[20], (AppProperties.Smtp) v[21]);
    }
}
