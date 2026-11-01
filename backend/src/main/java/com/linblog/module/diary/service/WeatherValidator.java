package com.linblog.module.content.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;

import java.util.Set;

/** 天气白名单校验（§5.4：8 个固定值）。 */
public final class WeatherValidator {

    public static final Set<String> ALLOWED = Set.of(
            "晴天", "多云", "大雪", "大雨", "阵风", "雷雨", "雾霾", "夜晚");

    private WeatherValidator() {
    }

    public static void validate(String weather) {
        if (weather == null || weather.isBlank() || !ALLOWED.contains(weather)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "天气取值不合法");
        }
    }
}
