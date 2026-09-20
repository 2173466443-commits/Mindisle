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
        private String dir = "./uploads";
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
}
