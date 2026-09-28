// 后端 com.mindisle.common.ErrorCode 的前端镜像表（制作步骤文档 §5.8 第 2 条）。
// 与后端枚举逐条核对（2026-09-28 实读 backend/src/main/java/com/mindisle/common/ErrorCode.java：
// 非零码 39 个 + SUCCESS(0)，本表 40 键）。两者不一致时以后端为准，判据用下面这条，别凭记忆数（它数的是含
//   SUCCESS 的全部枚举常量，2026-09-28 实测输出 40）：Select-String -Path backend/src/main/java/com/mindisle/common/ErrorCode.java -Pattern '^\s{4}[A-Z_]+\(' | Measure-Object -Line
// 阶段 5 补进的正是上次漏掉的那 6 个（30005/30006 私信、70001–70004 上传），
// 漏一个的症状不是报错，而是弹层显示「服务异常（code=30005）」这种没人看得懂的话。
export const ERROR_TEXT = {
  0: '成功',

  // 1xxxx 通用与鉴权
  10001: '参数不合法',
  10002: '请先登录',
  10003: '没有该操作权限',
  10004: '登录已过期，请重新登录',
  10005: '登录凭证无效',
  10006: '验证码错误',
  10007: '验证码已失效，请刷新后重试',
  10008: '用户名或密码错误',
  10009: '登录失败次数过多，请稍后再试',
  10010: '操作过于频繁，请稍后再试',

  // 2xxxx 用户与隐私
  20001: '用户不存在',
  20002: '用户名已被占用',
  20003: '账号已被停用',
  20004: '请先同意用户协议与隐私政策',
  20005: '该功能需要你对敏感个人信息处理单独授权',

  // 3xxxx 社区内容
  30001: '内容不存在或已删除',
  30002: '该内容不可见',
  30003: '今日评论次数已达上限',
  30004: '话题正在审核中',
  30005: '对方已开启隐私保护，无法发送私信',
  30006: '消息不存在',

  // 4xxxx AI 与情绪
  40001: 'AI 服务暂时不可用，请稍后再试',
  40002: '今日 AI 用量已达上限',
  40003: '文本太短，无法进行情绪分析',

  // 5xxxx 审核与危机
  50001: '内容包含违规信息，已拦截',
  50002: '该请求需要人工支持，已为你展示求助入口',
  50003: '审核任务不存在',

  // 6xxxx 推荐
  60001: '暂无可推荐内容',

  // 7xxxx 文件与上传
  70001: '图片超过单张 5MB 上限，请压缩后再试',
  70002: '只接受 jpg / png / gif 图片，且文件内容要与扩展名一致',
  70003: '图片无法读取，可能已损坏，换一张再试试',
  70004: '图片保存失败，请稍后重试',

  // 9xxxx 系统与降级
  90001: '该功能尚未实现',
  90002: '数据存储暂不可用',
  90003: '缓存服务暂不可用',
  90004: '服务开小差了，请稍后再试',
  90005: '本地缓存降级模式不支持该操作，请切换为 redis',
  90006: '接口或资源不存在',
  90007: '请求方法不被支持'
}

/** 常量引用，避免业务代码里冒出大量魔法数字。 */
export const CODE = {
  SUCCESS: 0,
  PARAM_INVALID: 10001,
  UNAUTHORIZED: 10002,
  FORBIDDEN: 10003,
  TOKEN_EXPIRED: 10004,
  TOKEN_INVALID: 10005,
  CAPTCHA_INVALID: 10006,
  CAPTCHA_EXPIRED: 10007,
  LOGIN_FAILED: 10008,
  LOGIN_LOCKED: 10009,
  RATE_LIMITED: 10010,
  USER_NOT_FOUND: 20001,
  USERNAME_TAKEN: 20002,
  PRIVACY_CONSENT_REQUIRED: 20004,
  SENSITIVE_CONSENT_REQUIRED: 20005,
  POST_NOT_FOUND: 30001,
  USER_DISABLED: 20003,
  POST_FORBIDDEN: 30002,
  PM_BLOCKED: 30005,
  PM_NOT_FOUND: 30006,
  // 30004 是本表里唯一「不是失败」的码：话题还在审核中，用户能做的是等。
  // 前端必须单独认出它，因为话题详情页要为此换一整套措辞（见 TopicDetailView 的 cardExtra），
  // 而 textOf 那句「话题正在审核中」在 StageNotice 之上还要补一句「阶段 3 没有放行通道」。
  TOPIC_PENDING: 30004,
  AI_UNAVAILABLE: 40001,
  AI_BUDGET_EXCEEDED: 40002,
  CONTENT_REJECTED: 50001,
  CRISIS_BLOCKED: 50002,
  RECOMMEND_EMPTY: 60001,
  NOT_IMPLEMENTED: 90001,
  DB_UNAVAILABLE: 90002,
  CACHE_UNAVAILABLE: 90003,
  INTERNAL_ERROR: 90004,
  RESOURCE_NOT_FOUND: 90006,
  METHOD_NOT_ALLOWED: 90007
}

/** 需要重新登录的三个真实码（后端枚举实测，不是臆造值）。 */
export function isAuthCode(code) {
  return code === CODE.UNAUTHORIZED || code === CODE.TOKEN_EXPIRED || code === CODE.TOKEN_INVALID
}

export function textOf(code, fallback) {
  if (Object.prototype.hasOwnProperty.call(ERROR_TEXT, code)) return ERROR_TEXT[code]
  return fallback || '服务异常（code=' + code + '）'
}
