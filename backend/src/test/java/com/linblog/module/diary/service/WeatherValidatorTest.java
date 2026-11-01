package com.linblog.module.diary.service;

import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.content.service.WeatherValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** §5.4 天气白名单：8 个固定值，非法值一律 40000。 */
class WeatherValidatorTest {

    @Test
    void allEightAllowedValuesPass() {
        assertEquals(8, WeatherValidator.ALLOWED.size());
        for (String weather : WeatherValidator.ALLOWED) {
            assertDoesNotThrow(() -> WeatherValidator.validate(weather), weather + " 应当合法");
        }
    }

    @Test
    void nullOrBlankIsRejected() {
        BizException exNull = assertThrows(BizException.class, () -> WeatherValidator.validate(null));
        assertEquals(ErrorCode.BAD_REQUEST, exNull.getErrorCode());
        assertEquals("天气取值不合法", exNull.getMessage());

        assertEquals("天气取值不合法",
                assertThrows(BizException.class, () -> WeatherValidator.validate("  ")).getMessage());
    }

    @Test
    void valueOutsideWhitelistIsRejected() {
        for (String weather : new String[]{"晴", "下雨", "SUNNY", "晴天 "}) {
            BizException ex = assertThrows(BizException.class, () -> WeatherValidator.validate(weather),
                    weather + " 应当被拒绝");
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        }
    }
}
