package com.mindisle.captcha;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.mindisle.cache.CacheService;
import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 图形验证码（任务 T2.16 · §5.11 第 1~2 条）。
 *
 * <p>零新增依赖：JDK 自带的 BufferedImage + ImageIO 就够，
 * 不引第三方 captcha 库，既减少许可证面，也方便答辩时逐行解释实现。</p>
 *
 * <p>三条硬约束：
 * ① 验证码文本只进缓存，key = cap:{captchaId}，TTL 300s，<b>不落库</b>（§5.11 明确写死的禁止项）；
 * ② 一次性：无论校验对错，取出即销毁，所以同一个 captchaId 二次提交必然被拒（防重放）；
 * ③ 运行期必须 headless（MindisleApplication 已设 java.awt.headless=true），
 *    否则服务器上 AWT 初始化会直接抛异常。</p>
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_LENGTH = 4;
    private static final Duration TTL = Duration.ofSeconds(300);
    private static final String KEY_PREFIX = "cap:";

    /** 去掉易混淆字符：0/O、1/I/L。校园用户里视力不便者不少，这是 FR10.2 可访问性的顺带收益。 */
    private static final String CHARS = "2345678ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** 与卡片背景色一致（theme 的 #16203A），深色治愈风格下验证码不会显得突兀。 */
    private static final Color BACKGROUND = new Color(0x16, 0x20, 0x3A);

    private final CacheService cacheService;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** 生成图形码，并把答案写入缓存。 */
    public Captcha generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        cacheService.set(KEY_PREFIX + captchaId, code.toString().toUpperCase(), TTL);
        return new Captcha(captchaId, toBase64Png(code.toString()), (int) TTL.getSeconds());
    }

    /**
     * 校验（一次性）。
     *
     * <p>先 getAndDelete 再比对：这样「答错」也会消耗掉这张图，
     * 攻击脚本每猜一次都必须重新申请一张，把在线爆破的成本抬到「取码开销 × 猜中概率」。</p>
     *
     * @throws BizException CAPTCHA_EXPIRED 未申请或已消耗；CAPTCHA_INVALID 答案不对
     */
    public void verifyOrThrow(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "请填写验证码");
        }
        String expected = cacheService.getAndDelete(KEY_PREFIX + captchaId, String.class);
        if (expected == null) {
            throw new BizException(ErrorCode.CAPTCHA_EXPIRED);
        }
        if (!expected.equalsIgnoreCase(captchaCode.trim())) {
            log.debug("验证码答案不匹配 captchaId={}", captchaId);
            throw new BizException(ErrorCode.CAPTCHA_INVALID);
        }
    }

    private String toBase64Png(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 3 条干扰线
            g.setStroke(new BasicStroke(1.4f));
            for (int i = 0; i < 3; i++) {
                g.setColor(lightColor(90));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT), random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            // 60 个噪点
            for (int i = 0; i < 60; i++) {
                g.setColor(lightColor(150));
                g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }
            // 4 位字符：逐字微旋转，破坏按列切分的简单 OCR
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            for (int i = 0; i < code.length(); i++) {
                double angle = (random.nextDouble() - 0.5d) * 0.45d;
                int x = 12 + i * 26;
                int y = 29 + random.nextInt(5);
                g.rotate(angle, x, y);
                g.setColor(lightColor(190));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.rotate(-angle, x, y);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("运行环境缺少 PNG 编码器，请确认 java.awt.headless 设置");
            }
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("验证码图片编码失败", e);
        }
    }

    /** 在深色底上取一个足够亮的随机色：亮度下限由参数控制，保证低视力用户也能读。 */
    private Color lightColor(int minBrightness) {
        int red = Math.max(minBrightness, random.nextInt(256));
        int green = Math.max(minBrightness, random.nextInt(256));
        int blue = Math.max(minBrightness, random.nextInt(256));
        return new Color(red, green, blue);
    }

    /**
     * GET /api/auth/captcha 的返回体。
     *
     * @param imageBase64 纯 Base64（不含 data: 前缀），前端自己拼 src，将来换 svg 不必改后端
     * @param ttlSeconds  有效期秒数，前端据此做倒计时提醒
     */
    public record Captcha(String captchaId, String imageBase64, int ttlSeconds) {
    }
}
