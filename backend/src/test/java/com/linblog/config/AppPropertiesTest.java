package com.linblog.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** app.* 配置的逗号分隔列表转换（§4.3）：trim、去空、类型转换。 */
class AppPropertiesTest {

    private AppProperties of(String allowedOrigins, String thumbWidths) {
        return new AppProperties(null, allowedOrigins, null, null, 0, null, false,
                null, null, null, null, null, null, thumbWidths, null,
                0, 0, 0, false, 0, 0, null);
    }

    @Test
    void allowedOriginListTrimsAndDropsBlanks() {
        assertEquals(List.of("https://a.com", "https://b.com"),
                of(" https://a.com , https://b.com ,, ", null).allowedOriginList());
    }

    @Test
    void allowedOriginListNullOrBlankIsEmpty() {
        assertTrue(of(null, null).allowedOriginList().isEmpty());
        assertTrue(of("   ", null).allowedOriginList().isEmpty());
    }

    @Test
    void thumbWidthListParsesIntegers() {
        assertEquals(List.of(320, 480, 1024), of(null, " 320 ,480, 1024 ").thumbWidthList());
    }

    @Test
    void thumbWidthListNullOrBlankIsEmpty() {
        assertTrue(of(null, null).thumbWidthList().isEmpty());
        assertTrue(of(null, "").thumbWidthList().isEmpty());
    }
}
