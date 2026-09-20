package com.mindisle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 系统参数 sys_config（需求 §7.2 #23 · FR8.6 · 手册 §5.1 表 29）。
 *
 * <p>需求 §12 规范 1「阈值不写死在代码里」的落地载体：情绪级联阈值、预算、危机关键词版本等
 * 全部按点分命名空间放这里，由管理端热更新，读取时按 valueType 转型。
 */
@Data
@TableName("sys_config")
public class SysConfig {

  @TableId(value = "id", type = IdType.AUTO)
  private Long id;

  /** 参数键，点分命名空间，如 risk.cascade_threshold。 */
  private String cfgKey;

  /** 参数值，json 型存 JSON 字符串。 */
  private String cfgValue;

  /** string / int / decimal / bool / json。 */
  private String valueType;

  /** 分组：rec / risk / prompt / audit / ai / misc，管理端按组分页。 */
  private String groupKey;

  /** 1 管理端可改，0 只读。 */
  private Integer editable;

  /** 含义与取值范围说明，同时是论文参数对照表来源。 */
  private String remark;

  /** 最后修改人，逻辑外键 user.id。 */
  private Long updatedBy;

  @TableLogic
  private Integer deleted;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
