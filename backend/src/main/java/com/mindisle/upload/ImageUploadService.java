package com.mindisle.upload;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.config.MindisleProperties;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 图片上传与重编码（任务 T3.1 · 需求 FR4.1、NFR7「上传类型白名单 + 重编码」）。
 *
 * <p>三道闸按「便宜的先跑」排序，避免拿一张几十兆的伪图去喂解码器：
 * ① 字节数（≤ upload.maxBytes，FR4.1 的 5MB）；② 魔数白名单，只认 jpg/png/gif，
 * <b>既不看扩展名也不看 Content-Type</b>，这两样客户端想写什么就写什么；③ 真解码，
 * 魔数对但解不出位图（截断、伪造头部）判 FILE_DECODE_FAILED，且<b>绝不落盘</b>。</p>
 *
 * <p>重编码是去 EXIF 唯一可靠的手段：EXIF 与 GPS 藏在 JPEG 的 APP1 段里，解码成 BufferedImage
 * 时这些段被直接丢掉，再编码也不会写回去。所以原图就算带着拍摄者手机型号和经纬度，
 * 落盘文件里也不会有（需求 §13 数据最小化）。代价要如实说明：<b>动图 GIF 只保留第一帧</b>。</p>
 *
 * <p>输出格式恒等于输入格式（png 进 png 出、gif 进 gif 出、jpg 进 jpg 出）：既避免
 * GIF→PNG 这类转码把体积撑破 5MB，也让 /uploads/** 的 Content-Type 与扩展名天然一致。
 * <b>这里没有用 WebP</b>：JDK 17 的 ImageIO 对 WebP 读和写都不支持（本机实测无 writer），
 * 而需求 FR4.1 的白名单本来就是 jpg/png/gif —— 手册 §6.1 任务 3.1 那句 uuid.webp 与需求不符，
 * 以需求为准。</p>
 */
@Service
public class ImageUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadService.class);

    /** 日期子目录，与手册 §6.1 任务 3.1 写的 uploads/yyyy/MM/dd 一致（Path.resolve 在 Windows 上也吃正斜杠）。 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 日志里允许出现的文件名长度上限，防止客户端用超长名字刷爆日志。 */
    /** 静态访问前缀（不含结尾斜杠），与 WebMvcConfig 的 /uploads/**、SecurityConfig 白名单里的同一条一致。 */
    public static final String URL_PREFIX = "/uploads";

    private static final int LOG_NAME_MAX = 64;

    /** 反斜杠单独取，源码里不出现该字符的字面量。 */
    private static final char BACKSLASH = (char) 92;

    /** 魔数嗅探结果，同时决定落盘扩展名。 */
    public enum Kind {
        PNG("png"), JPEG("jpg"), GIF("gif");

        private final String ext;

        Kind(String ext) {
            this.ext = ext;
        }

        public String ext() {
            return ext;
        }
    }

    /**
     * 上传结果。url 是给前端 img 标签直接用的相对路径（已在 SecurityConfig 放行 /uploads/**）；
     * width 与 height 是<b>重编码之后</b>回读落盘字节得到的真实像素，不是客户端声称的尺寸。
     */
    public record StoredImage(String url, String kind, int width, int height, long bytes) {
    }

    private final MindisleProperties properties;

    public ImageUploadService(MindisleProperties properties) {
        this.properties = properties;
    }

    /** 只嗅魔数，不看文件名与 Content-Type；不在白名单内返回 null。 */
    public static Kind sniff(byte[] raw) {
        if (raw == null) {
            return null;
        }
        if (startsWith(raw, new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return Kind.PNG;
        }
        if (startsWith(raw, new int[] {0xFF, 0xD8, 0xFF})) {
            return Kind.JPEG;
        }
        String head = new String(raw, 0, Math.min(raw.length, 6), StandardCharsets.US_ASCII);
        if ("GIF87a".equals(head) || "GIF89a".equals(head)) {
            return Kind.GIF;
        }
        return null;
    }

    /**
     * 校验 + 重编码 + 落盘。
     *
     * @param raw         原始字节，调用方（Controller）已从 multipart 里读全
     * @param claimedName 客户端声称的文件名，<b>只用于打日志</b>，绝不参与拼路径
     */
    public StoredImage store(byte[] raw, String claimedName) {
        MindisleProperties.Upload cfg = properties.getUpload();
        if (raw == null || raw.length == 0) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "没有收到图片内容");
        }
        if (raw.length > cfg.getMaxBytes()) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE, "图片 " + raw.length / 1024 / 1024
                    + "MB，超过单张 " + cfg.getMaxBytes() / 1024 / 1024 + "MB 上限");
        }
        Kind kind = sniff(raw);
        if (kind == null) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        String brief = briefName(claimedName);
        if (!brief.toLowerCase().endsWith("." + kind.ext())) {
            // 只告警不拒绝：文件名不是安全边界，魔数才是；按扩展名拒绝只会误伤把 PNG 存成 .jpg 的正常用户。
            log.warn("上传文件名与真实类型不符：claimed={} actual={}", brief, kind);
        }
        BufferedImage decoded = decode(raw);
        byte[] encoded = reencode(decoded, kind, cfg);
        BufferedImage onDisk = readBack(encoded);
        String day = LocalDate.now().format(DAY);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + kind.ext();
        Path target = root().resolve(day).resolve(filename);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, encoded);
        } catch (IOException e) {
            log.error("图片落盘失败：{}", target, e);
            throw new BizException(ErrorCode.FILE_STORE_FAILED);
        }
        return new StoredImage(URL_PREFIX + "/" + day + "/" + filename, kind.name().toLowerCase(),
                onDisk.getWidth(), onDisk.getHeight(), encoded.length);
    }

    /**
     * 把任务 3.1 返回的 URL 换回磁盘上的真实文件（发帖时的 9 张 / 20MB 一致性校验用它）。
     *
     * <p><b>为什么必须有这个方法</b>：上传与发帖是两个请求，「单帖 ≤9 张、共 ≤20MB」
     * 是发帖期才成立的整体约束（FR4.1），上传接口逐张判永远拦不住分 10 次上传。
     * 而发帖请求里客户端唯一可信的东西就是 URL —— 于是这里<b>只认 URL，
     * 尺寸、类型、字节数一律由服务端读盘重新得到</b>，客户端连「我这张图多大」都没机会谎报。</p>
     *
     * <p><b>路径穿越是这里的第一风险</b>：URL 来自客户端，必须逐段校验，
     * 任何 ".." 、绝对路径、盘符、URL 编码残留都在这里挡掉，
     * 并且最后用 {@code normalize().startsWith(root())} 再兜一道——
     * 校验「拼出来的路径仍在 upload 根目录内」，而不是校验「字符串里没有两个点」。
     * 前者是有效判定，后者能被我 percent-decode 出来的目录绕过。</p>
     *
     * <p>文件不存在或被人为删过，统一按「参数不合法」回绝（10001），
     * 而不是 70004 落盘失败：此刻系统没写坏任何东西，是客户端给了一个服务端不认的引用。</p>
     */
    public StoredImage inspect(String url) {
        Path base = root();
        Path target = resolveUnderBase(base, url);
        if (target == null || !Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "配图不存在或已失效，请重新上传");
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(target);
        } catch (IOException e) {
            log.warn("配图回读失败：{}", target, e);
            throw new BizException(ErrorCode.PARAM_INVALID, "配图读取失败，请重新上传");
        }
        Kind kind = sniff(raw);
        BufferedImage image = kind == null ? null : decodeQuiet(raw);
        if (image == null) {
            // 服务端自己写进去的文件读不出位图，说明目录被动过（运维事故而非用户错误），日志要能看出来。
            log.error("落盘文件不是可解码图片：{}", target);
            throw new BizException(ErrorCode.PARAM_INVALID, "配图已失效，请重新上传");
        }
        return new StoredImage(url, kind.name().toLowerCase(), image.getWidth(), image.getHeight(), raw.length);
    }

    /**
     * URL → 根目录内的真实路径；不在根目录内或形状不对返回 null。
     *
     * <p>包级可见是为了让单测能直接打穿越样本（"../../etc/passwd"、"C:/Windows/x.png"、
     * "/uploads/..%2f..%2f.env"）而不必绕整个服务。</p>
     */
    static Path resolveUnderBase(Path base, String url) {
        if (url == null || !url.startsWith(URL_PREFIX + "/") || url.indexOf('?') >= 0 || url.indexOf('#') >= 0) {
            return null;
        }
        String rel = url.substring(URL_PREFIX.length() + 1);
        // 百分号一并拒绝：服务端生成的文件名只有 hex、斜杠、点与扩展名，出现 % 就说明这个 URL 不是本服务给的。
        // 现在不把 "..%2f..%2f.env" 交给文件系统其实也逃不出去（没解码的两个点不是目录），
        // 但这条链路上将来可能换对象存储 SDK 或 URI 解析——任何一层做 percent-decode，它就会变成真穿越。
        if (rel.isEmpty() || rel.indexOf(BACKSLASH) >= 0 || rel.indexOf(0) >= 0 || rel.indexOf('%') >= 0) {
            return null;
        }
        Path candidate;
        try {
            candidate = base.resolve(rel).normalize();
        } catch (IllegalArgumentException e) {
            // 含非法字符（NUL、Windows 保留名等）时 JDK 直接抛，不往外传
            return null;
        }
        return candidate.startsWith(base) && !candidate.equals(base) ? candidate : null;
    }

    private static BufferedImage decodeQuiet(byte[] raw) {
        try {
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            return null;
        }
    }

    /** 落盘根目录的绝对路径，与 WebMvcConfig 映射 /uploads/** 用的是同一个配置项，两边不会指到不同目录。 */
    public Path root() {
        return Paths.get(properties.getUpload().getDir()).toAbsolutePath().normalize();
    }

    private static BufferedImage decode(byte[] raw) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) {
                // ImageIO 认不出的格式返回 null 而不抛异常，这里必须自己判，否则后面全是 NPE。
                throw new BizException(ErrorCode.FILE_DECODE_FAILED);
            }
            return image;
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_DECODE_FAILED);
        }
    }

    private static byte[] reencode(BufferedImage src, Kind kind, MindisleProperties.Upload cfg) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        int maxEdge = cfg.getMaxEdge();
        double scale = maxEdge > 0 && longest > maxEdge ? (double) maxEdge / (double) longest : 1.0d;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(src).scale(scale);
            if (kind == Kind.JPEG) {
                builder.outputQuality(cfg.getJpegQuality());
            }
            builder.outputFormat(kind.ext()).toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("图片重编码失败：kind={} {}x{}", kind, src.getWidth(), src.getHeight(), e);
            throw new BizException(ErrorCode.FILE_STORE_FAILED);
        }
    }

    private static BufferedImage readBack(byte[] encoded) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
            if (image == null) {
                throw new BizException(ErrorCode.FILE_STORE_FAILED);
            }
            return image;
        } catch (IOException e) {
            throw new BizException(ErrorCode.FILE_STORE_FAILED);
        }
    }

    private static boolean startsWith(byte[] data, int[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((data[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把客户端文件名压成一行可安全写进日志的短名：剥掉目录部分、丢掉控制字符、限长。
     * 上传路径完全由服务端生成，本方法只为防「日志伪造」和撑爆日志，不参与任何安全判定。
     */
    static String briefName(String claimed) {
        if (claimed == null || claimed.isEmpty()) {
            return "";
        }
        int cut = Math.max(claimed.lastIndexOf('/'), claimed.lastIndexOf(BACKSLASH));
        String name = cut >= 0 ? claimed.substring(cut + 1) : claimed;
        StringBuilder sb = new StringBuilder(Math.min(name.length(), LOG_NAME_MAX));
        for (int i = 0; i < name.length() && sb.length() < LOG_NAME_MAX; i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

