package com.mindisle.web;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mindisle.common.BizException;
import com.mindisle.common.ErrorCode;
import com.mindisle.common.Result;
import com.mindisle.security.AuthUser;
import com.mindisle.upload.ImageUploadService;
import com.mindisle.upload.ImageUploadService.StoredImage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 图片上传入口（任务 T3.1 · 需求 FR4.1、FR6.3 复用同一链路）。
 *
 * <p>一次调用传一张图：前端选多张就并发多次，服务端不需要在一次请求里管「共 ≤20MB」，
 * 单张 5MB 的闸由 ImageUploadService 与 spring.servlet.multipart.max-file-size 双重把。
 * 「单帖 ≤9 张」是发帖时的一致性约束，落在任务 T3.3，不在这里判（这里判了也拦不住分 10 次传）。</p>
 *
 * <p>必须登录：本接口会往磁盘写文件，游客可传等于送给自己的 DoS。
 * 返回的 url 是相对路径，/uploads/** 已在 SecurityConfig 放行，图片本身不含用户标识，
 * 但文件名是 UUID，猜不到别人的图片地址（需求 §13 的「不可枚举」最低要求）。</p>
 */
@RestController
@RequestMapping("/api/files")
@Tag(name = "7 文件", description = "图片上传（魔数白名单 + 重编码去 EXIF）")
public class FileController {

  private final ImageUploadService uploadService;

  public FileController(ImageUploadService uploadService) {
    this.uploadService = uploadService;
  }

  @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "上传一张图片，返回 /uploads/yyyy/MM/dd/uuid.ext 相对地址与真实像素（需登录）")
  public Result<StoredImage> uploadImage(@RequestPart("file") MultipartFile file,
                                         @AuthenticationPrincipal AuthUser current) {
    if (current == null) {
      // 与 AuditController 同样的兜底写法：白名单被误改时也不给游客开写盘的口子。
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    if (file == null || file.isEmpty()) {
      throw new BizException(ErrorCode.PARAM_INVALID, "没有收到文件");
    }
    byte[] raw;
    try {
      raw = file.getBytes();
    } catch (IOException e) {
      // 临时文件已被容器丢弃或磁盘读失败，对用户来说与「没传上来」是同一件事。
      throw new BizException(ErrorCode.FILE_DECODE_FAILED);
    }
    return Result.ok(uploadService.store(raw, file.getOriginalFilename()));
  }
}

