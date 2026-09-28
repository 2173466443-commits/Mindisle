package com.mindisle.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 隐私导出任务 export_task（任务 T4.21 · 需求 FR1.5 · 手册 §7.5）。
 *
 * <p>列名映射走 MyBatis-Plus 默认的 snake_case 到 camelCase 规则，故不逐列写 {@code @TableField}。
 * 只有两处不是纯映射：{@code fmt} 是刻意改名（见下），{@code rowCounts} 在库里是 JSON 列、
 * 这里当 String 存取。</p>
 *
 * <p><b>四个取舍全部来自 {@code sql/15_stage4_backfill.sql} 第 2 条的建表注释，抄到实体上是为了
 * 防止日后被「顺手改回去」</b>：</p>
 * <ol>
 *   <li><b>下载凭据用随机 token 而不是自增 id</b>：token 会进 URL、浏览器历史、代理与 access log。
 *       自增 id 既能被枚举（1、2、3…），又会顺带泄露「这个人已经导出过几次、全站导出过多少份」；
 *       随机口令两样都不暴露。这就是 {@code CHAR(64)} + {@code uk_token} 存在的全部理由。</li>
 *   <li><b>格式列叫 {@code fmt} 而不是 {@code format}</b>：FORMAT 是 MySQL 的保留词风险列表成员，
 *       在 DDL、Mapper、备份工具三处都要额外加反引号，加一次漏一次。</li>
 *   <li><b>{@code expire_at} 独立成列而不是 {@code created_at + 24h} 派生</b>：将来若有
 *       「重新发一次有效期」这个动作，派生写法没有落点，只能改代码；而独立列还能被 idx_expire 扫。</li>
 *   <li><b>产物只存相对路径</b>：导出根目录由配置给（{@code mindisle.privacy.export-dir}），
 *       把绝对路径写进库里等于把部署环境写进数据。</li>
 * </ol>
 *
 * <p><b>为什么 {@code rowCounts} 用 String 而不是 Map</b>：MySQL 的 JSON 列读回来本来就是文本，
 * 而 mybatis-plus 的 JSON 类型处理器要先注册才生效，未注册时会把读到的值变成 null ——
 * 那正是本项目在 {@code user.status} 上已经避开的同类坑（见 {@link com.mindisle.entity.User} 类注释）。
 * 这个字段今天只有两个读者：写它的服务和拿去核对行数的测试，都不需要 Map 形状的便利。</p>
 */
@Data
@TableName("export_task")
public class ExportTask {

  /** 任务态：请求即落 PENDING，后台线程跑完改 SUCCESS 或 FAILED（与 DDL 的 ENUM 逐字一致）。 */
  public static final String PENDING = "PENDING";
  public static final String RUNNING = "RUNNING";
  public static final String SUCCESS = "SUCCESS";
  public static final String FAILED = "FAILED";

  /** 两种格式（手册 §7.5「JSON + CSV 双格式导出」）。用小写，与 fmt 列的 ENUM 一致。 */
  public static final String FORMAT_JSON = "json";
  public static final String FORMAT_CSV = "csv";

  /** 主键，自增。<b>它永远不进下载链接</b>，理由见 {@link #token}。 */
  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 逻辑外键 user.id：导出者本人，只允许本人取（FR1.5）。 */
  private Long userId;

  /** json / csv。列名不叫 format：FORMAT 是 MySQL 的风险保留字，理由见 sql/15 第 2 条。 */
  private String fmt;

  private String status;

  /** 相对导出根目录的路径。库里刻意不存绝对路径：换机器、换容器、换盘符都不会让历史记录失效。 */
  private String filePath;

  /** 产物字节数。取文件时与文件系统现查值交叉校验，防止「库里说 2KB、磁盘上是个空文件」。 */
  private Long fileBytes;

  /** 每类各导出多少行的 JSON 原文。Gate4「条数与库内一致」的判据就是这一列，不靠人回忆。 */
  private String rowCounts;

  /** 下载口令：随机 32 字节十六进制，共 64 字符，库里 CHAR(64) + uk_token。 */
  private String token;

  /** 链接失效时刻 = 生成成功时刻 + 24 小时（手册 §7.5）。 */
  private LocalDateTime expireAt;

  /** FAILED 时的面向用户短句，不含堆栈、不含服务器路径（NFR7 可诊断但不泄露）。 */
  private String errorText;

  /** 软删留痕：删的是可下载性，不删「谁在何时导出过」这条合规记录。 */
  @TableLogic
  private Integer deleted;

  /** 提交时刻。 */
  private LocalDateTime createdAt;

  /** 状态变更时刻。 */
  private LocalDateTime updatedAt;
}

