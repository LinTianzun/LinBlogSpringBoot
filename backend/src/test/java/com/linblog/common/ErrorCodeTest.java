package com.linblog.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** §4.1 错误码 → HTTP 状态映射全表。 */
class ErrorCodeTest {

    @Test
    void httpMappingMatchesSpec() {
        assertEquals(0, ErrorCode.OK.code());
        assertEquals(HttpStatus.OK, ErrorCode.OK.httpStatus());

        assertEquals(40000, ErrorCode.BAD_REQUEST.code());
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.httpStatus());

        assertEquals(40001, ErrorCode.VALIDATION.code());
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION.httpStatus());

        assertEquals(40100, ErrorCode.UNAUTHENTICATED.code());
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED.httpStatus());

        assertEquals(40101, ErrorCode.ACCESS_EXPIRED.code());
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCode.ACCESS_EXPIRED.httpStatus());

        assertEquals(40102, ErrorCode.REFRESH_INVALID.code());
        assertEquals(HttpStatus.UNAUTHORIZED, ErrorCode.REFRESH_INVALID.httpStatus());

        assertEquals(40300, ErrorCode.FORBIDDEN.code());
        assertEquals(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN.httpStatus());

        assertEquals(40400, ErrorCode.NOT_FOUND.code());
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND.httpStatus());

        assertEquals(40900, ErrorCode.CONFLICT.code());
        assertEquals(HttpStatus.CONFLICT, ErrorCode.CONFLICT.httpStatus());

        assertEquals(42900, ErrorCode.RATE_LIMITED.code());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED.httpStatus());

        assertEquals(50000, ErrorCode.INTERNAL.code());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL.httpStatus());
    }

    @Test
    void everyCodeHasDefaultMessage() {
        for (ErrorCode ec : ErrorCode.values()) {
            org.junit.jupiter.api.Assertions.assertFalse(ec.defaultMessage() == null || ec.defaultMessage().isBlank(),
                    ec + " 缺少默认消息");
        }
    }

    @Test
    void bizExceptionCarriesCodeAndCustomMessage() {
        BizException custom = new BizException(ErrorCode.NOT_FOUND, "文章不存在");
        assertEquals(ErrorCode.NOT_FOUND, custom.getErrorCode());
        assertEquals("文章不存在", custom.getMessage());

        BizException fallback = new BizException(ErrorCode.NOT_FOUND);
        assertEquals("资源不存在", fallback.getMessage());

        BizException blank = new BizException(ErrorCode.FORBIDDEN, "");
        assertEquals("无权限", blank.getMessage());
    }
}
