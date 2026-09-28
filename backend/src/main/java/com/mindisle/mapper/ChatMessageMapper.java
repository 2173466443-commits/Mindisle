package com.mindisle.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mindisle.entity.ChatMessage;

/**
 * 消息体 Mapper（任务 T4.2 读历史 / T4.3 装配上下文）。
 *
 * <p>{@link #listLatest} 的「取最近 N 条」与「按时间正序返回」是两件事，
 * 这里一次 SQL 做完：子查询取 id 最大的 N 条，外层再按 id 升序 ——
 * 因为上下文装配要的正是「从旧到新」的对话顺序，交给 Java 再 reverse 一次
 * 看着省事，但「谁负责顺序」这件事一旦散在两处，就会出现同一份历史两种顺序。</p>
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

  /** 本会话最近 limit 条（未删除），返回顺序为时间正序，最多 200。 */
  @Select("SELECT * FROM (SELECT * FROM chat_message WHERE conversation_id = #{conversationId} "
      + "AND deleted = 0 ORDER BY id DESC LIMIT #{limit}) t ORDER BY t.id ASC")
  List<ChatMessage> listLatest(@Param("conversationId") long conversationId, @Param("limit") int limit);

  /** 会话内全部消息的回看接口用（U7 侧栏点开旧会话），按 id 正序。 */
  default List<ChatMessage> listByConversation(long conversationId, int limit) {
    return selectList(new LambdaQueryWrapper<ChatMessage>()
        .eq(ChatMessage::getConversationId, conversationId)
        .orderByAsc(ChatMessage::getId)
        .last("limit " + Math.max(1, Math.min(limit, 500))));
  }

  /** 归属校验：消息 → 会话 的 user_id 必须同时等于当前用户（防越权读别人的对话片段）。 */
  default ChatMessage findByIdOwned(long userId, long messageId) {
    return selectOne(new LambdaQueryWrapper<ChatMessage>()
        .eq(ChatMessage::getUserId, userId)
        .eq(ChatMessage::getId, messageId)
        .last("limit 1"));
  }

  @Select("SELECT COUNT(*) FROM chat_message WHERE conversation_id = #{conversationId} AND deleted = 0")
  int countByConversation(@Param("conversationId") long conversationId);

  /**
   * 用户消息里被识别出的情绪（任务 T4.9 的「被动识别」读侧）。
   *
   * <p>只取 {@code emotion_label} 非空的 user 行：助手消息不带情绪，
   * 把助手回复也算进「今天的情绪」会让一条长回答重复投票。</p>
   */
  @Select("SELECT * FROM chat_message WHERE user_id = #{userId} AND role = 'user' "
      + "AND emotion_label IS NOT NULL AND deleted = 0 ORDER BY id DESC LIMIT #{limit}")
  List<ChatMessage> listEmotionAnnotated(@Param("userId") long userId, @Param("limit") int limit);
}
