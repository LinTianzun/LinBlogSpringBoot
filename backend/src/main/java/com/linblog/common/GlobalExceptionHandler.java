package com.linblog.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理（§4.1/§4.2）：所有失败都输出统一响应壳。
 * 42900 额外附 Retry-After: 60 响应头。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String RETRY_AFTER_SECONDS = "60";

    public record FieldErrorItem(String field, String message) {
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBiz(BizException ex) {
        ErrorCode ec = ex.getErrorCode();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ec.httpStatus());
        if (ec == ErrorCode.RATE_LIMITED) {
            builder.header("Retry-After", RETRY_AFTER_SECONDS);
        }
        return builder.body(ApiResult.fail(ec, ex.getMessage()));
    }

    /** @Valid 请求体校验失败 → 40001，data.fieldErrors=[{field,message}]。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, List<FieldErrorItem>>>> handleBodyValidation(MethodArgumentNotValidException ex) {
        return validation(HttpStatus.BAD_REQUEST, ex.getBindingResult().getFieldErrors(), null);
    }

    /** @Validated 表单/绑定对象校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResult<Map<String, List<FieldErrorItem>>>> handleBind(BindException ex) {
        return validation(HttpStatus.BAD_REQUEST, ex.getBindingResult().getFieldErrors(), null);
    }

    /** @Validated 直接标注在方法参数（如 PageQuery）上的约束失败 → 40001。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Map<String, List<FieldErrorItem>>>> handleConstraint(ConstraintViolationException ex) {
        List<FieldErrorItem> items = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorItem(lastPathSegment(v), v.getMessage()))
                .toList();
        return validation(HttpStatus.BAD_REQUEST, List.of(), items);
    }

    /** 请求体 JSON 不可读（语法错误等）→ 40000。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.BAD_REQUEST, "请求体格式错误"));
    }

    /** 路径参数类型不匹配（如 /users/abc）→ 40000。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiResult.fail(ErrorCode.BAD_REQUEST, "参数类型错误: " + ex.getName()));
    }

    /** 静态资源不存在（含未匹配 API 路径）→ 40400。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResult.fail(ErrorCode.NOT_FOUND));
    }

    /** 兜底 50000：记 error 日志（含堆栈），响应壳带 requestId 但不泄露内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnknown(Exception ex) {
        log.error("Unhandled exception, requestId={}", ApiResult.currentRequestId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(ErrorCode.INTERNAL));
    }

    private ResponseEntity<ApiResult<Map<String, List<FieldErrorItem>>>> validation(
            HttpStatus status, List<FieldError> fieldErrors, List<FieldErrorItem> extra) {
        List<FieldErrorItem> items = fieldErrors.stream()
                .map(fe -> new FieldErrorItem(fe.getField(), fe.getDefaultMessage()))
                .collect(java.util.stream.Collectors.toList());
        if (extra != null) {
            items.addAll(extra);
        }
        Map<String, List<FieldErrorItem>> data = new LinkedHashMap<>();
        data.put("fieldErrors", items);
        return ResponseEntity.status(status).body(new ApiResult<>(
                ErrorCode.VALIDATION.code(), ErrorCode.VALIDATION.defaultMessage(), data, ApiResult.currentRequestId()));
    }

    private static String lastPathSegment(ConstraintViolation<?> v) {
        String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
