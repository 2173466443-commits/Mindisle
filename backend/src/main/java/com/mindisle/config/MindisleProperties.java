package com.mindisle.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
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

    @Data
    public static class Cache {
        private String mode = "local";
    }

    @Data
    public static class Llm {
        private String provider = "spring-ai";
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
}
