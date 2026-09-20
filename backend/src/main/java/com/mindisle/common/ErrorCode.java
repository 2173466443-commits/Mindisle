package com.mindisle.common;

/**
 * 全局错误码（手册 §5.5）。分段规则与论文第 6 章「接口设计规范」的错误码表一一对应：
 * 0 成功；1xxxx 通用与鉴权；2xxxx 用户与隐私；3xxxx 社区内容；4xxxx AI 与情绪；
 * 5xxxx 审核与危机；6xxxx 推荐；7xxxx 文件上传；9xxxx 系统与降级。
 *
 * <p>httpStatus 单独存放，是为了让业务失败与传输失败解耦：
 * 例如危机转介成功但内容被拦下时，HTTP 仍是 200，业务码 5xxxx 才反映真实结果。</p>
 */
public enum ErrorCode {

    SUCCESS(0, 200, "成功"),

    PARAM_INVALID(10001, 400, "参数不合法"),
    UNAUTHORIZED(10002, 401, "请先登录"),
    FORBIDDEN(10003, 403, "没有该操作权限"),
    TOKEN_EXPIRED(10004, 401, "登录已过期，请重新登录"),
    TOKEN_INVALID(10005, 401, "登录凭证无效"),
    CAPTCHA_INVALID(10006, 400, "验证码错误"),
    CAPTCHA_EXPIRED(10007, 400, "验证码已失效，请刷新后重试"),
    LOGIN_FAILED(10008, 401, "用户名或密码错误"),
    LOGIN_LOCKED(10009, 429, "登录失败次数过多，请稍后再试"),
    RATE_LIMITED(10010, 429, "操作过于频繁，请稍后再试"),

    USER_NOT_FOUND(20001, 404, "用户不存在"),
    USERNAME_TAKEN(20002, 409, "用户名已被占用"),
    USER_DISABLED(20003, 403, "账号已被停用"),
    PRIVACY_CONSENT_REQUIRED(20004, 403, "请先同意用户协议与隐私政策"),
    SENSITIVE_CONSENT_REQUIRED(20005, 403, "该功能需要你对敏感个人信息处理单独授权"),

    POST_NOT_FOUND(30001, 404, "内容不存在或已删除"),
    POST_FORBIDDEN(30002, 403, "该内容不可见"),
    COMMENT_TOO_MANY(30003, 429, "今日评论次数已达上限"),
    TOPIC_PENDING(30004, 409, "话题正在审核中"),

    AI_UNAVAILABLE(40001, 503, "AI 服务暂时不可用，请稍后再试"),
    AI_BUDGET_EXCEEDED(40002, 429, "今日 AI 用量已达上限"),
    EMOTION_SAMPLE_TOO_SHORT(40003, 400, "文本太短，无法进行情绪分析"),

    CONTENT_REJECTED(50001, 400, "内容包含违规信息，已拦截"),
    CRISIS_BLOCKED(50002, 400, "该请求需要人工支持，已为你展示求助入口"),
    AUDIT_TASK_NOT_FOUND(50003, 404, "审核任务不存在"),

    RECOMMEND_EMPTY(60001, 200, "暂无可推荐内容"),

    /**
     * 7xxxx 文件与上传（任务 T3.1 · 需求 FR4.1、NFR7 上传白名单与重编码）。
     * 提示文案直接面向用户，不暴露「服务器拒绝」这类技术词，也不暗示图片内容有问题。
     */
    FILE_TOO_LARGE(70001, 413, "图片超过单张 5MB 上限，请压缩后再试"),
    FILE_TYPE_NOT_ALLOWED(70002, 400, "只接受 jpg / png / gif 图片，且文件内容要与扩展名一致"),
    FILE_DECODE_FAILED(70003, 422, "图片无法读取，可能已损坏，换一张再试试"),
    FILE_STORE_FAILED(70004, 500, "图片保存失败，请稍后重试"),

    NOT_IMPLEMENTED(90001, 501, "该功能尚未实现"),
    DB_UNAVAILABLE(90002, 503, "数据存储暂不可用"),
    CACHE_UNAVAILABLE(90003, 503, "缓存服务暂不可用"),
    INTERNAL_ERROR(90004, 500, "服务开小差了，请稍后再试"),
    CACHE_OP_UNSUPPORTED_LOCAL(90005, 501, "本地缓存降级模式不支持该操作，请切换为 redis"),

    RESOURCE_NOT_FOUND(90006, 404, "接口或资源不存在"),
    METHOD_NOT_ALLOWED(90007, 405, "请求方法不被支持");

    private final int code;
    private final int httpStatus;
    private final String msg;

    ErrorCode(int code, int httpStatus, String msg) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMsg() {
        return msg;
    }

    /** 供前端镜像表与测试断言使用：按业务码反查枚举，找不到返回 null。 */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }
}
