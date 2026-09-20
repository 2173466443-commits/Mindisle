package com.mindisle.common;

import org.slf4j.MDC;

/**
 * 统一响应体（手册 §5.5）：所有 Controller 一律返回本类型，前端只需判断 code。
 *
 * <p>traceId 取自 MDC，由 TraceIdFilter 写入，用于把一次请求的日志、异常与前端提示串起来，
 * 是 NFR7「可诊断但不可泄露」的落地手段：响应体只给 traceId，绝不给堆栈。</p>
 */
public class Result<T> {

    /** 与 TraceIdFilter、logging.pattern 中的键保持一致。 */
    public static final String TRACE_ID_KEY = "traceId";

    private int code;
    private String msg;
    private T data;
    private String traceId;

    public static <T> Result<T> ok() {
        return of(ErrorCode.SUCCESS, null);
    }

    public static <T> Result<T> ok(T data) {
        return of(ErrorCode.SUCCESS, data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return of(errorCode, null);
    }

    /** 保留原始 code 但覆写文案的场景，例如把字段名拼进提示里。 */
    public static <T> Result<T> fail(int code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        result.traceId = currentTraceId();
        return result;
    }

    public static <T> Result<T> of(ErrorCode errorCode, T data) {
        Result<T> result = new Result<>();
        result.code = errorCode.getCode();
        result.msg = errorCode.getMsg();
        result.data = data;
        result.traceId = currentTraceId();
        return result;
    }

    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
