package com.mindisle.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * .env 加载器（制作步骤文档 §5.7，沿用 001 项目验证过的做法）。
 *
 * <p>取值优先级：真实环境变量 与 JVM 参数 高于 .env。实现方式是只有当系统属性
 * 为空时才 setProperty，因为 Spring 的 systemProperties、systemEnvironment 两个
 * 属性源本身就排在配置文件之前。</p>
 *
 * <p><b>顺序说明</b>：必须早于 ConfigDataEnvironmentPostProcessor
 * （HIGHEST_PRECEDENCE + 10）执行，否则 yml 中形如 JWT_SECRET 这类无默认值的
 * 占位符在绑定时就会解析失败。因此本类不能依赖已加载的 yml 来定位文件，
 * 只能按「显式参数 - 同名系统属性 - MINDISLE_ENV_FILE 环境变量 - ../.env」的顺序查找。</p>
 */
public class EnvLoader implements EnvironmentPostProcessor, Ordered {

    private static final Log log = LogFactory.getLog(EnvLoader.class);

    /** 文件首部可能带 UTF-8 BOM，必须剥掉，否则第一个键名会多出一个不可见字符。 */
    private static final char BOM = (char) 0xFEFF;
    private static final char DOUBLE_QUOTE = '"';
    private static final char EQUALS = '=';
    private static final String DEFAULT_LOCATION = "../.env";
    private static final String LOCATION_PROPERTY = "mindisle.env-file";
    private static final String LOCATION_ENV = "MINDISLE_ENV_FILE";

    private static volatile boolean loaded = false;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        load(readProperty(environment));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    /** 供启动类静态块调用：不指定路径，按默认顺序定位。 */
    public static void load() {
        load(null);
    }

    /** 幂等：无论被调用多少次，只真正加载一次。 */
    public static synchronized void load(String explicitLocation) {
        if (loaded) {
            return;
        }
        loaded = true;
        List<String> candidates = locations(explicitLocation);
        for (String candidate : candidates) {
            Path path = toPath(candidate);
            if (path == null || !Files.isReadable(path)) {
                continue;
            }
            int applied = apply(path);
            log.info("EnvLoader 已从 " + path.toAbsolutePath() + " 注入 " + applied + " 项配置");
            return;
        }
        log.warn("EnvLoader 未找到 .env 文件，尝试顺序 " + candidates
                + "。请把 .env.example 复制为 .env 并填写真实值，否则数据库与 JWT 无法初始化。");
    }

    private static List<String> locations(String explicitLocation) {
        List<String> list = new ArrayList<>();
        addIfPresent(list, explicitLocation);
        addIfPresent(list, System.getProperty(LOCATION_PROPERTY));
        addIfPresent(list, System.getenv(LOCATION_ENV));
        addIfPresent(list, DEFAULT_LOCATION);
        return list;
    }

    private static void addIfPresent(List<String> list, String value) {
        if (value != null && !value.trim().isEmpty() && !list.contains(value.trim())) {
            list.add(value.trim());
        }
    }

    private static Path toPath(String location) {
        try {
            return Paths.get(location);
        } catch (InvalidPathException e) {
            log.warn("EnvLoader 忽略非法路径：" + location);
            return null;
        }
    }

    private static int apply(Path path) {
        int applied = 0;
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("EnvLoader 读取 .env 失败：" + e.getMessage());
            return 0;
        }
        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            if (!line.isEmpty() && line.charAt(0) == BOM) {
                line = line.substring(1);
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring(7).trim();
            }
            int idx = line.indexOf(EQUALS);
            String key = idx <= 0 ? "" : line.substring(0, idx).trim();
            if (key.isEmpty()) {
                continue;
            }
            String value = unquote(line.substring(idx + 1).trim());
            if (System.getProperty(key) != null) {
                continue;
            }
            System.setProperty(key, value);
            applied++;
        }
        return applied;
    }

    /** 只处理成对的双引号：口令里出现空格时靠引号保留，单引号约定不支持。 */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == DOUBLE_QUOTE
                && value.charAt(value.length() - 1) == DOUBLE_QUOTE) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String readProperty(ConfigurableEnvironment environment) {
        try {
            return environment.getProperty(LOCATION_PROPERTY);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
