package com.linblog.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码 → HTTP 状态映射（开发文档 §4.1）。
 * 42900 由全局异常处理器附 Retry-After 响应头。
 */
public enum ErrorCode {
    OK(0, HttpStatus.OK, "ok"),
    BAD_REQUEST(40000, HttpStatus.BAD_REQUEST, "参数错误"),
    VALIDATION(40001, HttpStatus.BAD_REQUEST, "字段校验失败"),
    UNAUTHENTICATED(40100, HttpStatus.UNAUTHORIZED, "未认证"),
    ACCESS_EXPIRED(40101, HttpStatus.UNAUTHORIZED, "登录已过期"),
    REFRESH_INVALID(40102, HttpStatus.UNAUTHORIZED, "会话已失效，请重新登录"),
    FORBIDDEN(40300, HttpStatus.FORBIDDEN, "无权限"),
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(40900, HttpStatus.CONFLICT, "状态冲突"),
    RATE_LIMITED(42900, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    INTERNAL(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
