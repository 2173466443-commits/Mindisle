package com.mindisle.common;

/**
 * 业务异常（手册 §5.5）。只携带 ErrorCode 与可选的覆写文案，不携带 cause 链，
 * 避免异常被当作控制流滥用；系统级错误交给 GlobalExceptionHandler 的兜底分支。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    /** 同一错误码下给出更具体的提示，例如把命中的字段名带回前端。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BizException notImplemented(String stage) {
        return new BizException(ErrorCode.NOT_IMPLEMENTED, ErrorCode.NOT_IMPLEMENTED.getMsg() + "：" + stage);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
