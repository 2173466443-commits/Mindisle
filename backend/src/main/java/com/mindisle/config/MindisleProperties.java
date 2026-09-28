package com.mindisle.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本项目全部自定义配置（application.yml 的 mindisle 段）。
 * NFR10 要求不外置不写死：阈值、开关、密钥一律走这里，禁止在业务代码里出现魔法数字。
 */
@Data
@ConfigurationProperties(prefix = "mindisle")
public class MindisleProperties {

    /** 缓存实现开关：local（Caffeine，单机降级）或 redis（生产多实例）。 */
    private Cache cache = new Cache();
    /** 大模型接入实现开关：spring-ai、raw-http、mock（任务 4.1 的三实现）。 */
    private Llm llm = new Llm();
    /** .env 文件位置，实际读取由 EnvLoader 完成。 */
    private String envFile = "../.env";
    private Upload upload = new Upload();
    private Cors cors = new Cors();
    private Jwt jwt = new Jwt();
    private RateLimit rateLimit = new RateLimit();
    /** 大模型成本熔断阈值（需求 NFR8、FR6.7）。 */
    private AiBudget aiBudget = new AiBudget();
    /** 危机干预热线（FR8.4，读 sys_config 失败时的兜底值）。 */
    private Crisis crisis = new Crisis();
    /** 敏感词引擎（任务 T3.2 · 需求 FR7.1）。 */
    private Audit audit = new Audit();
    /** 发帖与评论频率（任务 T3.12 · 需求 BR4/BR5/BR6）。 */
    private Quota quota = new Quota();
    /** 发帖字段上限与树洞存活期（任务 3.3 · 需求 FR4.1、FR4.2）。 */
    private Post post = new Post();
    /** 举报阈值与举报入参上限（任务 T3.11 · 需求 FR4.7、FR4.4）。 */
    private Report report = new Report();
    /** 图形验证码（任务 T2.16 · §5.11 第 1~2 条）。 */
    private Captcha captcha = new Captcha();
    /** 站内搜索（任务 T3.9 · 需求 FR4.8）。 */
    private Search search = new Search();
    /** 话题创建与关注（任务 T3.8 · 手册 §6.1 行 3.8）。 */
    private Topic topic = new Topic();
    /**
     * 定时任务（任务 T4.20 情绪周报 · T4.21 数据保留清除）。
     *
     * <p>cron 与开关全部外置：手册 §7.5 给的是「周日晚 21:00」这一条口径，但它是运维口径
     * 而不是业务逻辑 —— 演示时想当场跑一批，改配置或传参即可，不必发版。
     * 名字用 {@code Schedule} 而不是 {@code Report}：{@code Report} 在本类里已被「举报」占用
     * （{@link Report} = 需求 FR4.7 的举报阈值），两个语义共用同一个键会让 yml 里
     * {@code mindisle.report.*} 同时属于两件事。</p>
     */
    private Schedule schedule = new Schedule();
    /**
     * 隐私中心（任务 T4.21 · 需求 FR1.6、NFR8、BR11、D11）。
     *
     * <p>单独一个子节而不是塞进 {@link Schedule}：{@code Schedule} 装的是「什么时候跑」，
     * 这里装的是「跑到什么程度、产物放哪、留几天」，两件事的变更理由不同 ——
     * 前者随运维窗口改，后者随合规口径改。键名用 {@code mindisle.privacy.*}，
     * 与 {@code /api/privacy/**} 这组端点同名，排查时一眼能对上。</p>
     */
    private Privacy privacy = new Privacy();

    @Data
    public static class Cache {
        private String mode = "local";
    }

    /**
     * 大模型接入（任务 T4.1 三实现开关 + T4.3 上下文预算 + T4.13 成本熔断）。
     *
     * <p><b>api-key 与 spring.ai.deepseek.api-key 是同一个环境变量的两次绑定，不是两份真值</b>：
     * Spring AI 那条给 SpringAiLlmClient 用（它自己读 spring.ai.* 装配 ChatModel bean），
     * 这一条给 RawHttpLlmClient 用（它不经过 Spring AI，必须自己拿 Key）。
     * 两处都写 {@code ${DEEPSEEK_API_KEY}}，所以轮换密钥只改 .env 一个地方。
     * 之所以要绑两次而不是一方引用另一方：{@code spring.ai.*} 是第三方命名空间，
     * 让我们的代码去读它，等于把「配置从哪来」这件事写进业务代码里。</p>
     *
     * <p><b>{@code @ToString.Exclude} 是硬要求</b>：这个类是 {@code @Data}，
     * Lombok 会把每个字段编进 toString()。而 MindisleProperties 是 bean，
     * 任何一次「顺手把配置对象打进日志」的调试语句都会把密钥写进日志文件——
     * 而日志盘是可读的吗？是。排除掉这一位，事故就不可能发生。</p>
     *
     * <p>{@code thinkingEnabled} 默认 <b>false</b>，是 dev-log 记的「事实 B」的直接后果：
     * deepseek-flash 是思考型模型，请求里不带 {@code thinking:{type:"disabled"}} 时，
     * 实测首包 484 个字符全在 reasoning_content 里、content 长度为 0，
     * 于是 SSE 一个字都上不了屏，界面表现是「转圈转到超时」。
     * 关掉之后 firstContentMs=601ms。这个字段必须由单测钉住（见 SpringAiLlmClientThinkingTest），
     * 因为它是那种「改回默认值就静默失效」的配置——没有异常、没有日志、只有白屏。</p>
     */
    @Data
    public static class Llm {
        /** spring-ai | raw-http | mock。见 {@code LlmClientConfiguration} 的装配分支。 */
        private String provider = "spring-ai";
        private String baseUrl = "https://api.deepseek.com";
        /** 只从环境变量来，仓库内不出现明文；同时被排除出 toString，见类注释。 */
        @ToString.Exclude
        private String apiKey;
        /** 默认模型名。deepseek-flash 为思考型，必须配 thinkingEnabled=false 才能流式上屏。 */
        private String model = "deepseek-flash";
        private double temperature = 0.7;
        /** 单次生成上限（FR2.2 的回复本来就要求短，800 token 足够，多给只会多花钱）。 */
        private int maxTokens = 800;
        /** 思考链开关，默认关，理由见类注释。 */
        private boolean thinkingEnabled = false;
        private int connectTimeoutMs = 5000;
        /** 读超时：单帧之间最长可容忍的空窗（DeepSeek 思考时可能几秒不吐字）。 */
        private int readTimeoutMs = 60000;
        /** SseEmitter 的总时长上限，手册 T4.5 写的 30s。 */
        private int sseTimeoutMs = 30000;
        /** 每 1k input token 的单价，单位「分」。上线前必须按官网核对（见 dev-log）。 */
        private double priceInCentPer1k = 0.1d;
        /** 每 1k output token 的单价，单位「分」。 */
        private double priceOutCentPer1k = 0.2d;
        /** 单用户保留的活跃会话数上限（任务 T4.2「超出逻辑删除最旧」）。 */
        private int keepConversations = 50;
        /** 会话标题取首条用户消息的前 N 字（T4.2）。 */
        private int titleMaxChars = 20;
        /** 上下文带最近几轮（T4.3，一轮 = 一问一答）。 */
        private int contextRounds = 8;
        /** 超过这个轮数触发摘要压缩（T4.3）。 */
        private int summaryTriggerRounds = 12;
        /** 摘要字数上限（T4.3，落库前再截一次，因为模型不总是听话）。 */
        private int summaryMaxChars = 200;
        /** 上下文的 token 预算，估算是超了就截断并置 truncated=true（T4.3）。 */
        private int contextMaxTokens = 6000;
        /** BR12：置信度低于这个值的记录不进趋势线。 */
        private double emotionConfidentMin = 0.6d;
        /** 级联闸门：词典通道置信度低于这个值就升级送 LLM（需求 §8.1、创新点 1）。 */
        private double emotionLlmFallbackBelow = 0.55d;
        /** 风险双通道的模型侧总开关（T4.11）。关掉时只走词面规则，工单照建，论文可对照。 */
        private boolean riskLlmEnabled = true;
        /** 连续失败几次进熔断（T4.13）。 */
        private int circuitFailThreshold = 3;
        /** 熔断后多久放行一次试探（半开），毫秒。 */
        private long circuitHalfOpenMs = 60000;
    }

    @Data
    public static class Upload {
        /** 落盘根目录（相对后端工作目录）；静态映射 /uploads/** 见 WebMvcConfig。 */
        private String dir = "./uploads";
        /** 单张字节上限（需求 FR4.1：单张 ≤5MB）。服务端自己的这道闸排在解码之前，不依赖容器配置。 */
        private long maxBytes = 5L * 1024 * 1024;
        /** 重编码后的长边上限（手册 §6.1 任务 3.1：1600px）。只缩不放，小图不会被插值放大。 */
        private int maxEdge = 1600;
        /** JPEG 重编码质量；PNG 与 GIF 是无损的，这个值对它们无效。 */
        private float jpegQuality = 0.82f;
        /** 单帖图片张数上限（需求 FR4.1：0–9 张），真正的校验发生在发帖（任务 T3.3）。 */
        private int maxImagesPerPost = 9;
        /**
         * 单帖配图总字节上限（需求 FR4.1「共 ≤20MB」）。
         *
         * <p>这道闸只能在发帖时判（任务 3.3）：上传接口是分次调的，一次 5MB 合法，
         * 第十次也合法，只有把「本帖要挂哪几张」收齐了才知道总量超没超。
         * 字节数取服务端读盘的真实大小，不信客户端声称的 size。</p>
         */
        private long maxTotalBytes = 20L * 1024 * 1024;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Data
    public static class Jwt {
        private String secret;
        private long accessMinutes = 120;
        private long refreshDays = 7;
    }

    @Data
    public static class RateLimit {
        /** NFR7：普通接口 60 次/分钟。 */
        private int userPerMinute = 60;
        /** NFR7：AI 接口 6 次/分钟（成本与雪崩保护）。 */
        private int aiPerMinute = 6;
        /** §5.11 T2.16：连错 5 次锁 10 分钟。 */
        private int loginFailMax = 5;
        private int loginLockMinutes = 10;
    }

    @Data
    public static class AiBudget {
        private long userDailyTokens = 200000;
        private long globalDailyCostCent = 10000;
        private double alertRatio = 0.8;
    }

    @Data
    public static class Crisis {
        private String hotline = "12356";
        /** L2 触发分与展示分：需求 §5.2「AI 判定 risk ≥0.6」即 L2。 */
        private double l2Score = 0.6d;
        /** L3 触发分：需求 §5.2「双通道同时高分（risk ≥0.8）」，词面规则通道取 0.9 偏保守。 */
        private double l3Score = 0.9d;
        /** L2 认领时限（小时），需求 §5.2。 */
        private int l2SlaHours = 4;
        /** L3 认领时限（分钟），需求 §5.2。 */
        private int l3SlaMinutes = 30;
        /** 工单证据片段最大字数（FR10.5「脱敏后 200 字上下文」）。 */
        private int evidenceChars = 200;
    }

    /**
     * 发帖入参约束（任务 3.3）。
     *
     * <p>这些数字全部来自需求 FR4.1/FR4.2，但按 NFR10 不许在业务代码里写死，
     * 统一走这里：改一处配置，DTO 校验、服务端复检、提示文案三处同步变。</p>
     */
    @Data
    public static class Post {
        /** 标题最长字符数（FR4.1：≤50）。 */
        private int maxTitleChars = 50;
        /** 正文最长字符数（FR4.1：≤5000）。 */
        private int maxContentChars = 5000;
        /** 评论最长字符数（FR4.4「≤1000 字」，与 DDL 的 comment.content VARCHAR(1000) 同宽）。 */
        private int maxCommentChars = 1000;
        /** 单帖话题数上限：需求只写「+ 话题」没给数字，取 3（超过 3 个属于引流，FR1.7 防刷屏）。 */
        private int maxTopics = 3;
        /**
         * 未显式传 visibility 时的默认值（需求 FR4.1 只有 public / private 两档）。
         *
         * <p>三种帖型共用这一个默认：树洞的「匿名」是<b>不露真实身份</b>，不是「不给人看」——
         * 匿名倾诉拿到回应正是本产品的闭环（需求 §1.2「说出来 → 被理解 → 被回应」），
         * 把 hole 默认成 private 会让树洞 tab 永远空着。</p>
         */
        private String defaultVisibility = "public";
        /**
         * 树洞可选存活时长（小时）。手册 §6.1 行 836 的「选项 24h/72h/7d」，
         * 且必须在发帖时算成绝对时间落 auto_destroy_at，不能在查询时现算。
         */
        private List<Integer> holeDestroyOptions = new ArrayList<>(List.of(24, 72, 168));
        /** 勾选树洞但没指定时长时的默认值（FR4.2「默认 7 天后」= 168 小时）。 */
        private int holeDefaultDestroyHours = 168;
    }

    /**
     * 举报约束（任务 T3.11 · 需求 FR4.7 与 FR4.4）。
     *
     * <p>{@code autoReviewThreshold} 是 FR4.4「report_cnt 达阈值自动转 HUMAN_REVIEW」里那个
     * 一直没落地的阈值：阶段 2 建 post 表时先占了列，真相表（content_report）到 T3.11 才建，
     * 所以这一列在此之前始终是 0。取 3 的理由写进手册 §14：一条内容被三个人独立举报，
     * 在校园里已经足够说明「不是私人恩怨」，再高的阈值会让明显违规的内容多活好几天。</p>
     */
    @Data
    public static class Report {
        /** 转人审阈值，按<b>举报人数</b>算（同一人重复举报不叠加，uk_reporter_target）。 */
        private int autoReviewThreshold = 3;
        /** 举报描述字数上限（FR4.7「描述」；列宽 500 是余量，这里才是产品口径）。 */
        private int maxDescriptionChars = 200;
        /** 截图证据张数上限（FR4.7「截图证据」）。 */
        private int maxEvidenceImages = 3;
        /** 非危机举报的处理时限（小时）。危机类走 crisis 的 L2/L3 SLA，不读这一项。 */
        private int slaHours = 24;
    }

    @Data
    public static class Audit {
        /**
         * jar 内置词库快照位置，由 docs/export-sensitive-dict.mjs 从 sql/09_seed.sql 导出。
         *
         * <p><b>前缀不能省</b>：运行期注入的 ResourceLoader 是 Servlet Web 容器上下文，
         * 裸路径会被当成「Web 应用根目录下的文件」去找（ServletContext resource [/dict/...]），
         * 打包运行必然找不到；只有显式 classpath: 才走类路径。阶段 3 冒烟实测踩过这条，
         * 引擎里也做了兜底归一，但配置仍以显式前缀为准。</p>
         */
        private String dictResource = "classpath:dict/sensitive_words_v0.1.txt";
        /**
         * 可写的盘外词库快照。配了就优先读它——jar 里的资源运行时改不了，
         * 没有这个口子，手册 §6.1 要求的「热更新」就只能重启进程，等于没做。
         */
        private String dictPath = "";
        /** 两次比对词库版本号的最小间隔（毫秒），防止每次检测都读一次缓存。 */
        private long dictCheckMillis = 60000L;
        /** 词库版本广播键，管理端 T6.2 改词后写它。 */
        private String dictVersionKey = "dict:version";
    }

    @Data
    public static class Quota {
        /** BR5：新注册保护期时长（小时）。 */
        private int newbieWindowHours = 24;
        /** BR5：保护期内每日发帖上限。 */
        private int newbieDailyPosts = 5;
        /** BR5：常规用户每日发帖上限。 */
        private int dailyPosts = 20;
        /** BR4：单用户对单帖的每日评论上限。 */
        private int dailyCommentsPerPost = 20;
    }

    @Data
    public static class Captcha {
        /**
         * 验证码总开关。
         *
         * <p><b>默认 true 是刻意的 fail-closed</b>：漏配这个键时系统仍然要求验证码，
         * 而不是悄悄敞开注册/登录接口。唯一允许改成 false 的场合是本地开发与
         * 自动化冒烟——因为验证码答案只进缓存、不返回明文也不打日志，
         * 脚本永远拿不到它，于是「注册 → 登录 → 带 token 调业务接口」这条
         * 最需要真实验证的链路会被永远挡在门外（阶段 2/3 的 200 响应体欠账即由此而来）。</p>
         */
        private boolean enabled = true;
    }

    /**
     * 话题域约束（任务 3.8 · 手册 §6.1 行 3.8「创建话题需 audit_status=待审（防刷）」）。
     *
     * <p>{@code maxNameChars} / {@code maxDescChars} 与 DDL 的列宽逐字对齐
     * （{@code sql/04_community.sql} 表 15：name VARCHAR(32) / desc_txt VARCHAR(200)）。
     * 两处必须同时改：只改配置，超长会在 INSERT 处被 MySQL 判 1406 变成 90004；
     * 只改列宽，配置就成了一个骗人的上限。报告这两个数字前先在 {@code SHOW CREATE TABLE} 上核对。</p>
     *
     * <p>{@code maxCreatePerDay} 是手册那句「防刷」的唯一落地手段：阶段 3 没有管理员审核台
     * （T6.1），一个脚本号一秒钟就能造出几百个待审话题把话题墙淹掉。取 5 的理由：
     * 一个正常用户一天内想开的新圈子很少超过这个数，而它又足够让「一天建五个不同话题
     * 试探敏感词库」变得不划算。这是配额，不是产品主张，改配置不改代码。</p>
     */
    @Data
    public static class Topic {
        /** 话题名最长字符数，与 topic.name 的 VARCHAR(32) 同宽。 */
        private int maxNameChars = 32;
        /** 话题简介最长字符数，与 topic.desc_txt 的 VARCHAR(200) 同宽。 */
        private int maxDescChars = 200;
        /** 单账号每日可创建话题数上限（手册 §6.1 行 3.8「防刷」）。 */
        private int maxCreatePerDay = 5;
        /**
         * 话题预审开关 —— <b>需求 FR8.6「系统配置：话题预审开关」就是这一行</b>。
         *
         * <p>默认 true 是照抄 FR4.5 的字面要求（「话题创建需管理员审核（防刷屏）」）：
         * 开 = 用户新建的话题一律落 {@code audit_status='PENDING'}，机审结论只是
         * 附在日志里的一条参考，不改变结果；关 = 走机审直通，BLOCK 拒、REVIEW 转待审、
         * 干净就直接 APPROVED。</p>
         *
         * <p><b>为什么阶段 3 只敢默认 true 而不敢做「关掉就全自动」的第二档</b>：
         * {@code audit_task.target_type} 的 ENUM 里没有 {@code topic}（{@code sql/04_community.sql} 表 20），
         * 也就是说今天<b>没有一条通道能把一个 PENDING 话题放行成 APPROVED</b> —— 管理端在 T6.1。
         * 于是默认值下「用户新建话题」在阶段 3 的实际结局是停在待审，
         * 这也是任务 3.8 只能标 ◐ 而不是 ☑ 的唯一原因（详见 docs/dev-log.md 阶段 3（续 9））。
         * 把开关默认打开而不是偷偷做「机审直通」，是因为后者会让人以为
         * 「审核」这一环已经存在了 —— 而它还不存在。</p>
         *
         * <p>这个配置可以按环境覆盖：本地开发与冒烟想验证「新建话题立刻能挂帖」这条链路，
         * 设 {@code MINDISLE_TOPIC_PRE_REVIEW=false} 即可，不必改代码；
         * 但那条链路在生产语义下不是默认行为，所以文档与取证都要注明用的是哪个值。</p>
         */
        private boolean requirePreReview = true;
    }

    @Data
    public static class Search {
        /** 关键词长度上限（字）。与 post.title 的 VARCHAR(100) 不在一个量级是故意的：没人用一句话去搜。 */
        private int maxKeywordChars = 64;
        /** 搜话题 / 搜人一次返回的条数上限，同时也是缺省值被夹住的上界。 */
        private int maxProfiles = 20;
        /**
         * 全文通道总开关。
         *
         * <p><b>默认 false 是刻意的</b>：{@code MATCH(title, content)} 依赖
         * {@code sql/10_index.sql} 里的 {@code ft_title_content}，而那个脚本至今没在开发库执行
         * （任务 2.2 仍是 ◐）。开着它 = 每次搜索先抛一次 1191 再被读侧回落接住，
         * 白付一次往返。读侧的回落逻辑一直都在（见 {@code PostQueryService#search}），
         * 这里默认关是「别让默认配置走一条已知会抛异常的路」，而不是「全文没做」。</p>
         */
        private boolean fulltext = false;
    }

    /**
     * 定时任务配置（任务 T4.20 · 手册 §7.5）。
     *
     * <p>这里每一个批次上限都是<b>钱闸</b>：跑一次周报 = 一次 LLM 调用。上限放在配置里，
     * 是为了让「今天先少跑点」这种决定不需要改代码，也让 FR2.10 的日预算有一条能被调的闸。
     * cron 用 Spring 六段式（秒 分 时 日 月 周）。</p>
     */
    @Data
    public static class Schedule {
        /**
         * 情绪周报批次的 cron，缺省「每周日 21:00:00」。
         *
         * <p>{@code ? * SUN} 里那个 {@code ?} 是 Spring/Quartz 六段式的写法：「日」与「周」
         * 不能同时指定，用 {@code ?} 占住「日」这一位。把它换成 {@code *} 会让每个既是 1 号
         * 又是周日的日子多命中一次；写成五位则直接解析失败。这一串由 {@code WeeklyReportJobTest}
         * 用 {@code CronExpression.parse} 真算了一次下一个触发时刻来钉住 —— 光断言字符串相等
         * 挡不住「语法合法但跑错日子」。</p>
         */
        private String weeklyReportCron = "0 0 21 ? * SUN";
        /** 周报批次总开关。关掉时任务入口打一条 INFO 说明本次跳过，而不是沉默。 */
        private boolean weeklyReportEnabled = true;
        /**
         * 单批最多重算多少个用户的周报（手册 §7.5「单批 ≤ 200 人」）。
         *
         * <p>它只能把上界调小：{@code WeeklyReportJob.MAX_BATCH} 是代码里的硬上限，
         * 配置写 10000 也会被夹回 200。理由是这条约束的存在理由（一次批次别把日预算吃穿）
         * 不随环境改变 —— 能被配置突破的闸不是闸。</p>
         */
        private int weeklyReportBatchLimit = 200;
        /**
         * 数据保留清除的 cron，缺省「每天 03:30:00」。
         *
         * <p>消费方是 T4.21 的 {@code DataRetentionJob}（冷静期届满后的物理清除）。
         * 选凌晨三点：与周报的周日 21:00 错开，且这段时间在线人数最低 ——
         * 清除是一次跨多表的大批 DELETE，撞在演示时间会当场把库锁住。</p>
         */
        private String retentionCron = "0 30 3 * * ?";
        /** 清除任务开关。关掉 = 冷静期永远不到期、注销用户的数据一直留着（演示期可以，交付不行）。 */
        private boolean retentionEnabled = true;
        /**
         * 单批最多物理清除几个账号（手册 §7.5「到期清除」）。
         *
         * <p>缺省 50 而不是 200：清除一个账号 = 一次跨 20 多张表的 DELETE 事务，
         * 比周报的一次 LLM 调用重得多，批次开大了会把库锁在演示时间之外的一整段时间里。
         * 与周报同一个纪律：这个值只能把上界调小，{@code DataRetentionJob.MAX_BATCH} 才是上界。</p>
         */
        private int retentionBatchLimit = 50;
    }

    /**
     * 隐私中心配置（任务 T4.21）。四个键分别管一件事：产物落哪、链接活多久、
     * 冷静期多长、单表最多导出多少行。
     */
    @Data
    public static class Privacy {
        /**
         * 导出产物目录。缺省相对路径 {@code ./data/privacy-export}，
         * 与上传目录同一套「相对启动目录」的口径（见 {@code FileController}）。
         *
         * <p>产物是<b>全库最敏感的一批文件</b>：一个 zip 里装着某个人的全部聊天、情绪、
         * 帖子与授权流水。所以它不在静态资源目录里（否则任何人拼得出路径），
         * 只能通过 {@code GET /api/privacy/export/file} 带 JWT 与口令取。</p>
         */
        private String exportDir = "./data/privacy-export";
        /** 下载链接有效期（小时），需求 FR1.6 原文是「24h 失效」。 */
        private int linkTtlHours = 24;
        /**
         * 注销冷静期（天）。手册 §7.5 给 30 天：期内登录即自动撤回注销。
         *
         * <p>{@code <= 0} 会被 {@code CoolingState#purgeTime} 夹回 1 天，
         * 因为「配成 0 = 提交即清除」是把主体权利做成了自毁开关。</p>
         */
        private int coolingDays = 30;
        /**
         * 单表单次最多导出的行数。
         *
         * <p>它挡的是「导出把堆吃穿」这一件事：chat_message 与 ai_call_log 是最容易长到几十万行的表，
         * 一次全量读进内存做 JSON 序列化，演示机上会先 OOM 再谈合规。
         * 截断不是静默行为 —— 读到 limit 行时 {@code truncated} 会记进 {@code row_counts} 的对账，
         * 概览页与导出包都能看到「这一域被截断了」。</p>
         */
        private int maxRowsPerTable = 5000;
    }
}
