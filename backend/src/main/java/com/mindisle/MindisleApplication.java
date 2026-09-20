package com.mindisle;

import com.mindisle.config.EnvLoader;
import com.mindisle.config.MindisleProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 心屿 MindIsle 服务端启动类（制作步骤文档 §5.3）。
 *
 * <p>静态代码块在 Spring 容器启动之前完成 .env 注入，这是 Windows 本地开发下
 * 「application.yml 里的占位符解析失败」这一首个坑的根治手段（§5.7）。
 * 同时把 headless 设为 true：验证码（§5.11 T2.16）与图片压缩（§6.1 任务 3.1）
 * 都要用 java.awt，以服务方式启动时没有显示设备。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(MindisleProperties.class)
@MapperScan("com.mindisle.**.mapper")
public class MindisleApplication {

    static {
        EnvLoader.load();
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(MindisleApplication.class, args);
    }
}
