package com.linblog.common;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §4.1/§4.2 全局异常处理：统一响应壳、fieldErrors、Retry-After、500 不泄露内部细节。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 供 MethodParameter 使用的哑方法。 */
    @SuppressWarnings("unused")
    static class Dummy {
        void handler(Sample body) {
        }
    }

    static class Sample {
        @Min(1)
        int page;
    }

    @BeforeEach
    void setUp() {
        MDC.put("requestId", "req-test-001");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void bizExceptionMapsCodeAndHttpStatus() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleBiz(
                new BizException(ErrorCode.FORBIDDEN, "账号已禁用"));

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertEquals(40300, resp.getBody().code());
        assertEquals("账号已禁用", resp.getBody().message());
        assertNull(resp.getBody().data());
        assertEquals("req-test-001", resp.getBody().requestId());
    }

    @Test
    void bizExceptionWithBlankMessageFallsBackToDefault() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleBiz(
                new BizException(ErrorCode.UNAUTHENTICATED, " "));

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertEquals(40100, resp.getBody().code());
        assertEquals("未认证", resp.getBody().message());
    }

    @Test
    void rateLimitedAddsRetryAfterHeader() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleBiz(
                new BizException(ErrorCode.RATE_LIMITED, "慢点"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        assertEquals("60", resp.getHeaders().getFirst("Retry-After"));
        assertEquals(42900, resp.getBody().code());
    }

    @Test
    void otherErrorsHaveNoRetryAfterHeader() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleBiz(
                new BizException(ErrorCode.CONFLICT, "状态冲突"));

        assertNull(resp.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void bodyValidationReturns40001WithFieldErrors() throws Exception {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Sample(), "sample");
        br.addError(new FieldError("sample", "page", "必须大于等于 1"));
        Method m = Dummy.class.getDeclaredMethod("handler", Sample.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(new MethodParameter(m, 0), br);

        ResponseEntity<ApiResult<Map<String, List<GlobalExceptionHandler.FieldErrorItem>>>> resp =
                handler.handleBodyValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(40001, resp.getBody().code());
        assertEquals("字段校验失败", resp.getBody().message());
        List<GlobalExceptionHandler.FieldErrorItem> items = resp.getBody().data().get("fieldErrors");
        assertEquals(1, items.size());
        assertEquals("page", items.get(0).field());
        assertEquals("必须大于等于 1", items.get(0).message());
        assertEquals("req-test-001", resp.getBody().requestId());
    }

    @Test
    void bindExceptionReturns40001WithFieldErrors() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Sample(), "sample");
        br.addError(new FieldError("sample", "page", "必须大于等于 1"));
        BindException ex = new BindException(br);

        ResponseEntity<ApiResult<Map<String, List<GlobalExceptionHandler.FieldErrorItem>>>> resp =
                handler.handleBind(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(40001, resp.getBody().code());
        assertEquals("page", resp.getBody().data().get("fieldErrors").get(0).field());
    }

    @Test
    void constraintViolationUsesLastPathSegmentAsField() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<jakarta.validation.ConstraintViolation<Sample>> violations = validator.validate(new Sample());
        assertEquals(1, violations.size());
        jakarta.validation.ConstraintViolationException ex =
                new jakarta.validation.ConstraintViolationException(violations);

        ResponseEntity<ApiResult<Map<String, List<GlobalExceptionHandler.FieldErrorItem>>>> resp =
                handler.handleConstraint(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(40001, resp.getBody().code());
        List<GlobalExceptionHandler.FieldErrorItem> items = resp.getBody().data().get("fieldErrors");
        assertEquals(1, items.size());
        assertEquals("page", items.get(0).field());
        assertTrue(items.get(0).message() != null && !items.get(0).message().isBlank());
    }

    @Test
    void unreadableBodyMapsTo40000() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("broken json", new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiResult<Void>> resp = handler.handleUnreadable(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(40000, resp.getBody().code());
        assertEquals("请求体格式错误", resp.getBody().message());
    }

    @Test
    void typeMismatchMapsTo40000WithParamName() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", null, new NumberFormatException("For input: \"abc\""));

        ResponseEntity<ApiResult<Void>> resp = handler.handleTypeMismatch(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(40000, resp.getBody().code());
        assertEquals("参数类型错误: id", resp.getBody().message());
    }

    @Test
    void noResourceMapsTo40400() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/missing.js");

        ResponseEntity<ApiResult<Void>> resp = handler.handleNoResource(ex);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(40400, resp.getBody().code());
        assertEquals("资源不存在", resp.getBody().message());
        assertNull(resp.getBody().data());
    }

    @Test
    void unknownExceptionMapsTo50000WithoutLeakingDetails() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleUnknown(
                new IllegalStateException("jdbc:mysql://10.0.0.1/secret-db password=hunter2"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals(50000, resp.getBody().code());
        assertEquals("服务器内部错误", resp.getBody().message());
        assertNull(resp.getBody().data());
    }
}
