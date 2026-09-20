package com.mindisle.common;

import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 全局异常处理（手册 §5.5）。三条硬规则：
 * 1. 响应体绝不透出堆栈与 SQL 片段（NFR7）；
 * 2. 日志必带 traceId，与 Result.traceId 同源，便于用户报障时定位；
 * 3. HTTP 状态码取 ErrorCode.httpStatus，业务码取 ErrorCode.code，两者不混用。
 *
 * <p>注意：过滤器链上抛出的 AccessDeniedException 由 SecurityConfig 里的
 * AccessDeniedHandler 负责（那时还没进 DispatcherServlet），本类只处理方法级
 * @PreAuthorize 与 Controller 内抛出的那一种。这是最容易漏的一处双写。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("业务异常 {} {} path={}", code.getCode(), e.getMessage(), currentPath());
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe).collect(Collectors.joining("；"));
        return toBiz(ErrorCode.PARAM_INVALID, detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(item -> item.getPropertyPath() + " " + item.getMessage())
                .collect(Collectors.joining("；"));
        return toBiz(ErrorCode.PARAM_INVALID, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析 path={}：{}", currentPath(), e.getMessage());
        return toBiz(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMsg() + "：请求体不是合法 JSON");
    }

    /**
     * 数据层异常单独成档（手册 §5.5 补充）。
     *
     * <p>如果不拦，MySQL 停机 / 连接池耗尽会被下面的 Exception 兜底成 90004 + 500，
     * 前端只能提示「服务开小差了，请报障」，用户和我们都分不清是「稍后重试就好」还是
     * 「代码有 bug」。回 90002 + 503 之后，前端按 5xx 走自动重试，按 90004 走人工报障。</p>
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> handleDb(DataAccessException e) {
        log.error("数据层异常 path=" + currentPath(), e);
        return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.getHttpStatus())
                .body(Result.fail(ErrorCode.DB_UNAVAILABLE.getCode(), "数据暂时读取不到，请稍后重试"));
    }

    /**
     * 事务开启失败（实测踩坑）：@Transactional 方法在「拿连接」阶段抛的
     * CannotCreateTransactionException 继承自 TransactionException，
     * 而 TransactionException 直接继承 NestedRuntimeException、**不是** DataAccessException，
     * 因此不会被上一个 handler 接住，会掉进 Exception 兜底变成 90004/500。
     * 建库之前每一次登录/注册都会踩到，必须与 DataAccessException 同级归为 90002/503。
     */
    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<Result<Void>> handleTransaction(TransactionException e) {
        log.error("事务/连接不可用 path=" + currentPath(), e);
        return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.getHttpStatus())
                .body(Result.fail(ErrorCode.DB_UNAVAILABLE.getCode(), "数据存储暂不可用，请先完成建库（docs/init-db.ps1）"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        log.warn("无此资源 path={}{}", currentPath(), e.getResourcePath() == null ? "" : " static=" + e.getResourcePath());
        return toBiz(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getMsg() + "：" + requestLine());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("方法不被支持 path={} supported={}", currentPath(), e.getSupportedHttpMethods());
        return toBiz(ErrorCode.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED.getMsg() + "：" + requestLine());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("越权访问 path={}", currentPath());
        return toBiz(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMsg());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        // 堆栈只进日志，绝不进响应体（NFR7）
        log.error("未预期异常 path=" + currentPath(), e);
        return toBiz(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMsg());
    }

    private String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private ResponseEntity<Result<Void>> toBiz(ErrorCode code, String msg) {
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code.getCode(), msg));
    }

    /** 取「方法 + 路径」用于 404/405 的提示文案；取不到就返回 unknown。 */
    private String requestLine() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                    .getRequest();
            return request.getMethod() + " " + request.getRequestURI();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private String currentPath() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                    .getRequest();
            return request.getMethod() + " " + request.getRequestURI();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
