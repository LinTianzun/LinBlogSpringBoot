package com.linblog.common;

import org.slf4j.MDC;

/**
 * 统一响应壳（开发文档 §4.1）：
 * { "code": 0, "message": "ok", "data": { }, "requestId": "8f3a…" }
 */
public record ApiResult<T>(int code, String message, T data, String requestId) {

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(ErrorCode.OK.code(), ErrorCode.OK.defaultMessage(), data, currentRequestId());
    }

    public static ApiResult<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode, String message) {
        String msg = message == null || message.isBlank() ? errorCode.defaultMessage() : message;
        return new ApiResult<>(errorCode.code(), msg, null, currentRequestId());
    }

    public static <T> ApiResult<T> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    /** 由请求审计过滤器写入 MDC；过滤器链之前的响应（401/403 壳）同样可取到。
     *  命名避开组件名 requestId——record 组件访问器不允许同名静态方法。 */
    public static String currentRequestId() {
        String v = MDC.get("requestId");
        return v == null ? "" : v;
    }
}
