package com.mindisle.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import com.mindisle.upload.ImageUploadService.Kind;
import com.mindisle.upload.ImageUploadService.StoredImage;

/**
 * 图片上传单测（任务 T3.1 · 需求 FR4.1、NFR7「白名单 + 重编码」）。
 *
 * <p>不启 Spring、不用 Mockito：ImageUploadService 的全部外部依赖只有 MindisleProperties
 * 与一个磁盘目录，@TempDir 给的就是真目录，测出来的 url、文件名、落盘字节、回读像素全是真的。
 * 之所以坚持真解码真落盘，是因为这条链路上最容易骗自己的地方就是
 * 「只测了嗅探没测编码」——JDK 有没有某个格式的 writer，只有真的写一次才知道。</p>
 *
 * <p>安全断言只挑能被静态字节证明的写：伪造扩展名、路径穿越、超长文件名、带 EXIF 的 JPEG；
 * 需要 HTTP 容器与登录态的部分（401、multipart 大小闸由谁先拒）不在此类，见手册 §6.1 的实测口径。</p>
 *
 * <p>落盘位置与 url 的关系是「/uploads/ 是挂载点」而不是「root() 下面还有个 uploads 目录」：
 * WebMvcConfig 把 /uploads/** 映射到 root() 本身，所以本类还原真实文件时先把前缀剥掉。</p>
 */
class ImageUploadServiceTest {

    /** 上传目录的访问前缀，与 WebMvcConfig、SecurityConfig 里的字面量对齐；测试故意写死字面量，不去引用服务常量。 */
    private static final String MOUNT = "/uploads/";

    @TempDir
    Path tmp;

    private MindisleProperties properties;
    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        properties = new MindisleProperties();
        properties.getUpload().setDir(tmp.toString());
        service = new ImageUploadService(properties);
    }

    @Test
    @DisplayName("嗅探只认魔数：png/jpeg/gif 命中，文本、zip、pdf、半截 PNG 头与 null 都不在名单")
    void sniffOnlyTrustsMagicBytes() throws IOException {
        assertThat(ImageUploadService.sniff(png(8, 8))).isEqualTo(Kind.PNG);
        assertThat(ImageUploadService.sniff(jpeg(8, 8))).isEqualTo(Kind.JPEG);
        assertThat(ImageUploadService.sniff(gif(8, 8))).isEqualTo(Kind.GIF);
        assertNull(ImageUploadService.sniff("This is not an image at all.".getBytes(StandardCharsets.US_ASCII)));
        assertNull(ImageUploadService.sniff(new byte[] { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00 }));
        assertNull(ImageUploadService.sniff("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertNull(ImageUploadService.sniff(new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 }));
        assertNull(ImageUploadService.sniff(null));
        assertThat(Kind.PNG.ext()).isEqualTo("png");
        assertThat(Kind.JPEG.ext()).isEqualTo("jpg");
        assertThat(Kind.GIF.ext()).isEqualTo("gif");
    }

    @Test
    @DisplayName("把文本文件改名成 .png 传上来：判类型不合法，且一个字节都不落盘")
    void rejectsDisguisedTextFile() throws IOException {
        byte[] shell = pngScriptLikeText();
        BizException e = assertThrows(BizException.class, () -> service.store(shell, "shell.png"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        assertThat(storedCount()).isZero();
    }

    @Test
    @DisplayName("空 body：null 与零字节都按「没收到图片」拒绝，不进解码器")
    void rejectsEmptyBody() throws IOException {
        assertThat(assertThrows(BizException.class, () -> service.store(new byte[0], "a.png"))
                .getErrorCode()).isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        assertThat(assertThrows(BizException.class, () -> service.store(null, "a.png"))
                .getErrorCode()).isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        assertThat(storedCount()).isZero();
    }

    @Test
    @DisplayName("超字节上限的闸排在解码之前：6MB 伪 PNG 直接 70001，不喂解码器也不落盘")
    void rejectsOversizedBeforeDecoding() throws IOException {
        byte[] big = new byte[6 * 1024 * 1024];
        System.arraycopy(png(16, 16), 0, big, 0, 16);
        BizException e = assertThrows(BizException.class, () -> service.store(big, "big.png"));
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE);
        assertThat(e.getMessage()).contains("6MB").contains("5MB");
        assertThat(properties.getUpload().getMaxBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(storedCount()).isZero();
    }

    @Test
    @DisplayName("魔数对但内容坏了：真 PNG 只留 8 字节签名、GIF89a 后接垃圾，都判 70003 且不落盘")
    void rejectsCorruptBodyWithValidMagic() throws IOException {
        byte[] truncated = Arrays.copyOf(png(64, 64), 8);
        assertThat(ImageUploadService.sniff(truncated)).isEqualTo(Kind.PNG);
        assertThat(assertThrows(BizException.class, () -> service.store(truncated, "t.png"))
                .getErrorCode()).isEqualTo(ErrorCode.FILE_DECODE_FAILED);

        byte[] fakeGif = ("GIF89a" + " this is not a bitmap stream").getBytes(StandardCharsets.US_ASCII);
        assertThat(ImageUploadService.sniff(fakeGif)).isEqualTo(Kind.GIF);
        assertThat(assertThrows(BizException.class, () -> service.store(fakeGif, "f.gif"))
                .getErrorCode()).isEqualTo(ErrorCode.FILE_DECODE_FAILED);
        assertThat(storedCount()).isZero();
    }

    @Test
    @DisplayName("落盘走 uploads/yyyy/MM/dd/uuid32.png，url 与真实像素由回读给出")
    void storesUnderDateDirectoryAndReportsRealDims() throws IOException {
        StoredImage stored = service.store(png(120, 80), "avatar.png");
        assertThat(service.root()).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(stored.url()).startsWith("/uploads/");
        String[] parts = stored.url().split("/");
        LocalDate today = LocalDate.now();
        assertThat(parts).hasSize(6);
        assertThat(parts[1]).isEqualTo("uploads");
        assertThat(parts[2]).isEqualTo(String.format("%04d", today.getYear()));
        assertThat(parts[3]).isEqualTo(String.format("%02d", today.getMonthValue()));
        assertThat(parts[4]).isEqualTo(String.format("%02d", today.getDayOfMonth()));
        assertThat(parts[5]).hasSize(36).endsWith(".png");
        assertThat(parts[5].substring(0, 32)).matches("[0-9a-f]{32}");
        Path onDisk = absoluteOf(stored);
        assertTrue(Files.exists(onDisk), "落盘文件要真实存在");
        assertThat(stored.bytes()).isEqualTo(Files.size(onDisk));
        assertThat(stored.kind()).isEqualTo("png");
        assertThat(stored.width()).isEqualTo(120);
        assertThat(stored.height()).isEqualTo(80);
    }

    @Test
    @DisplayName("输出格式恒等于输入格式：png/gif/jpg 各回各家，扩展名与 kind 都对得上")
    void keepsSourceFormat() throws IOException {
        StoredImage fromPng = service.store(png(40, 30), "a.png");
        StoredImage fromGif = service.store(gif(40, 30), "a.gif");
        StoredImage fromJpg = service.store(jpeg(40, 30), "photo.jpg");
        assertThat(fromPng.url()).endsWith(".png");
        assertThat(fromGif.url()).endsWith(".gif");
        assertThat(fromJpg.url()).endsWith(".jpg");
        assertThat(fromPng.kind()).isEqualTo("png");
        assertThat(fromGif.kind()).isEqualTo("gif");
        assertThat(fromJpg.kind()).isEqualTo("jpeg");
        assertThat(storedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("PNG 透明通道要活过重编码：需求要的是头像/截图不发虚，不能悄悄转成 JPEG")
    void preservesPngAlpha() throws IOException {
        BufferedImage argb = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                argb.setRGB(x, y, 0xFF336699);
            }
        }
        argb.setRGB(0, 0, 0x00FF0000);
        StoredImage stored = service.store(write(argb, "png"), "alpha.png");
        BufferedImage saved = ImageIO.read(absoluteOf(stored).toFile());
        assertNotNull(saved);
        assertTrue(saved.getColorModel().hasAlpha(), "落盘 PNG 仍要有 alpha 通道");
        assertThat(saved.getRGB(0, 0) >>> 24).isZero();
        assertThat((saved.getRGB(20, 20) >> 16) & 0xFF).isEqualTo(0x33);
        assertThat((saved.getRGB(20, 20) >> 8) & 0xFF).isEqualTo(0x66);
    }

    @Test
    @DisplayName("同样的字节传两次得到两个不同文件名：文件名是 UUID，不覆盖别人也不被别人覆盖")
    void sameBytesGetUniqueNames() throws IOException {
        byte[] same = png(24, 24);
        StoredImage first = service.store(same, "dup.png");
        StoredImage second = service.store(same, "dup.png");
        assertThat(first.url()).isNotEqualTo(second.url());
        assertTrue(Files.exists(absoluteOf(first)));
        assertTrue(Files.exists(absoluteOf(second)));
        assertThat(storedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("长边压到 1600 且保持宽高比；小图只缩不放，不会被插值放大")
    void downscalesLongEdgeOnly() throws IOException {
        StoredImage wide = service.store(png(2400, 800), "wide.png");
        assertThat(wide.width()).isEqualTo(1600);
        assertThat((double) wide.width() / wide.height()).isCloseTo(3.0d, within(0.05d));
        StoredImage small = service.store(png(10, 10), "small.png");
        assertThat(small.width()).isEqualTo(10);
        assertThat(small.height()).isEqualTo(10);
    }

    @Test
    @DisplayName("长边上限来自配置而不是写死：改成 80 之后 400x200 就变成 80x40")
    void maxEdgeComesFromConfiguration() throws IOException {
        properties.getUpload().setMaxEdge(80);
        StoredImage stored = service.store(png(400, 200), "cfg.png");
        assertThat(stored.width()).isEqualTo(80);
        assertThat(stored.height()).isEqualTo(40);
    }

    @Test
    @DisplayName("重编码真的剥掉 EXIF：注入合法 APP1 的 JPEG 落盘后既无 Exif 字符串也无 APP1 标记")
    void stripsExifOnReencode() throws IOException {
        byte[] dirty = injectExifApp1(jpeg(64, 48));
        assertTrue(containsAscii(dirty, "Exif"), "样本本身要含 EXIF，否则这条断言是空的");
        assertTrue(containsSegment(dirty, 0xFF, 0xE1), "样本本身要含 APP1 段");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(dirty)), "注入 APP1 之后 JPEG 仍必须可解码");

        StoredImage stored = service.store(dirty, "phone.jpg");
        byte[] saved = Files.readAllBytes(absoluteOf(stored));
        assertThat(containsAscii(saved, "Exif")).isFalse();
        assertThat(containsSegment(saved, 0xFF, 0xE1)).isFalse();
        assertThat(containsAscii(saved, "Java 1.4")).isFalse();
        assertThat(stored.kind()).isEqualTo("jpeg");
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(saved));
        assertThat(back.getWidth()).isEqualTo(64);
        assertThat(back.getHeight()).isEqualTo(48);
    }

    @Test
    @DisplayName("客户端文件名穿不出去：相对路径、反斜杠、绝对路径三种写法都只影响日志")
    void clientFileNameCannotEscapeTheRoot() throws IOException {
        String bs = String.valueOf((char) 92);
        StoredImage posix = service.store(png(16, 16), "../../evil.png");
        StoredImage windows = service.store(png(16, 16), ".." + bs + ".." + bs + "evil.png");
        StoredImage absolute = service.store(png(16, 16), tmp.resolveSibling("evil.png").toString());
        Path base = tmp.toAbsolutePath().normalize();
        for (StoredImage stored : Arrays.asList(posix, windows, absolute)) {
            assertThat(stored.url()).doesNotContain("..").startsWith("/uploads/");
            Path real = absoluteOf(stored).toAbsolutePath().normalize();
            assertTrue(real.startsWith(base), "落盘路径要在上传根目录内");
            assertTrue(Files.exists(real));
        }
        assertFalse(Files.exists(tmp.resolveSibling("evil.png")), "上传目录之外不能被写出文件");
        assertThat(storedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("日志用的短名：剥目录、吃控制字符防日志伪造、限长 64")
    void briefNameIsLogSafe() {
        String bs = String.valueOf((char) 92);
        assertThat(ImageUploadService.briefName(null)).isEmpty();
        assertThat(ImageUploadService.briefName("")).isEmpty();
        assertThat(ImageUploadService.briefName("/etc/passwd")).isEqualTo("passwd");
        assertThat(ImageUploadService.briefName("../../evil.png")).isEqualTo("evil.png");
        assertThat(ImageUploadService.briefName("C:" + bs + "Users" + bs + "me.png")).isEqualTo("me.png");
        assertThat(ImageUploadService.briefName("ok" + (char) 10 + (char) 0 + (char) 27 + ".png")).isEqualTo("ok.png");
        assertThat(ImageUploadService.briefName("x".repeat(300) + ".png")).hasSize(64);
        assertThat(ImageUploadService.briefName("a" + (char) 0 + "b")).isEqualTo("ab");
    }

    /** 上传目录之外的位置不该有文件，用它数一数真实落盘了几个。 */
    private long storedCount() throws IOException {
        try (var walk = Files.walk(tmp)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /** url 里的 uploads 是挂载点不是目录：剥掉前缀再往 root() 下面逐段 resolve，不拼字符串。 */
    private Path absoluteOf(StoredImage stored) {
        assertThat(stored.url()).startsWith(MOUNT);
        Path path = service.root();
        String relative = stored.url().substring(MOUNT.length());
        for (String segment : relative.split("/")) {
            path = path.resolve(segment);
        }
        return path;
    }

    private static byte[] pngScriptLikeText() {
        return "rm -rf / -- I am a shell script, not a PNG".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] png(int width, int height) throws IOException {
        return write(canvas(width, height), "png");
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        return write(canvas(width, height), "jpg");
    }

    private static byte[] gif(int width, int height) throws IOException {
        return write(canvas(width, height), "gif");
    }

    private static BufferedImage canvas(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x33, 0x66, 0x99));
        g.fillRect(0, 0, Math.max(1, width / 2), height);
        g.setColor(new Color(0xCC, 0x99, 0x66));
        g.fillRect(Math.max(1, width / 2), 0, Math.max(1, width - width / 2), height);
        g.dispose();
        return image;
    }

    /** 断言 JDK 真有这个格式的 writer：返回 false 时 ImageIO 会静默不写，测试就会变成假绿。 */
    private static byte[] write(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        assertTrue(ImageIO.write(image, format, out), "JDK 镜像缺少 " + format + " 编码器");
        return out.toByteArray();
    }

    /**
     * 手工往 JPEG 的 SOI 之后插一段 APP1/Exif（内容是合法的空 IFD：II + 42 + 偏移 8 + 0 个条目）。
     *
     * <p>不这么做就没法在单机上验「去 EXIF」：JDK 的 JPEG writer 根本不会写 EXIF，
     * 拿一张干净的 JPEG 去断言「落盘没有 EXIF」等于什么都没测。</p>
     */
    private static byte[] injectExifApp1(byte[] jpeg) throws IOException {
        byte[] head = new byte[] { 'E', 'x', 'i', 'f', 0, 0 };
        byte[] tiff = new byte[] { 'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0 };
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + 32);
        out.write(jpeg, 0, 2);
        int length = head.length + tiff.length + 2;
        out.write(0xFF);
        out.write(0xE1);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(head);
        out.write(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static boolean containsAscii(byte[] data, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        return containsSegment(data, asciiCodes(target));
    }

    private static int[] asciiCodes(byte[] data) {
        int[] codes = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            codes[i] = data[i] & 0xFF;
        }
        return codes;
    }

    private static boolean containsSegment(byte[] data, int... segment) {
        outer: for (int i = 0; i + segment.length <= data.length; i++) {
            for (int j = 0; j < segment.length; j++) {
                if ((data[i + j] & 0xFF) != segment[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
