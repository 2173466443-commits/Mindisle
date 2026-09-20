package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 帖子配图 post_image（需求 §7.2 #5 · 手册 §5.1 表 13）。
 *
 * <p><b>宽高与 hash 一律由服务端读盘得到</b>：任务 3.1 的 {@code StoredImage} 已经是
 * 「重编码后回读落盘字节」的真实像素，发帖时再按 url 查一次盘，客户端没有可谎报的字段。
 * hash 供秒传与重复上传排查，V1 只做记录不做去重（去重要引引用计数，属过度设计）。</p>
 */
@Data
@TableName("post_image")
public class PostImage {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 post.id。 */
  private Long postId;

  /** 静态相对路径，形如 /uploads/2026/09/20/&lt;32 位十六进制&gt;.png。 */
  private String url;

  /** 展示顺序，0 起。 */
  private Integer sort;

  private Integer width;

  private Integer height;

  /** 文件 MD5（32 位十六进制），CHAR(32) NOT NULL DEFAULT ''。 */
  private String hash;

  @TableLogic
  private Integer deleted;
}
