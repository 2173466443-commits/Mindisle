package com.mindisle;

import com.mindisle.config.EnvLoader;
import com.mindisle.config.MindisleProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 心屿 MindIsle 服务端启动类（制作步骤文档 §5.3）。
 *
 * <p>静态代码块在 Spring 容器启动之前完成 .env 注入，这是 Windows 本地开发下
 * 「application.yml 里的占位符解析失败」这一首个坑的根治手段（§5.7）。
 * 同时把 headless 设为 true：验证码（§5.11 T2.16）与图片压缩（§6.1 任务 3.1）
 * 都要用 java.awt，以服务方式启动时没有显示设备。</p>
 *
 * <p><b>{@code @EnableScheduling}（任务 T4.20 引入）</b>：本项目的第一个定时任务是
 * 情绪周报批次（周日 21:00），没有这一行它的 {@code @Scheduled} 只是个普通注解、
 * 永远不会被触发 —— 而且<b>启动不报错、接口不报错、单测全绿</b>，唯一症状是
 * 「周报永远不会自己长出来」，那是最难发现的一类漏配。第二个消费者是 T4.21 的
 * {@code DataRetentionJob}（注销冷静期届满后的物理清除，{@code sql/15} 的 {@code purge_at}
 * 列等的就是它）。两个任务还要各自过配置开关（{@code mindisle.schedule.*-enabled}）
 * 才真动手，所以「容器里有调度器」与「今晚会不会跑」是两件事：后者可按环境关掉，
 * 且关掉时会在日志里留一行 INFO，不是静默。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(MindisleProperties.class)
@MapperScan("com.mindisle.**.mapper")
@EnableScheduling
public class MindisleApplication {

    static {
        EnvLoader.load();
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(MindisleApplication.class, args);
    }
}
