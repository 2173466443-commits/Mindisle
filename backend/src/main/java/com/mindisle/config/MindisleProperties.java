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
}
