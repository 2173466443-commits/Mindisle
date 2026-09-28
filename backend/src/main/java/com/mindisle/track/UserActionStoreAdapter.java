package com.mindisle.track;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.mindisle.entity.UserAction;
import com.mindisle.mapper.EmotionRecordMapper;
import com.mindisle.mapper.UserActionMapper;

/**
 * 埋点存储端口的 MyBatis 真实现（任务 T3.10 · {@link UserActionRecorder.Store} 的生产实现）。
 *
 * <p>与 {@code PostInteractionStoreAdapter} 同一套路：本类只做「翻译」，不掺业务判断，
 * 也<b>不吞异常</b>——吞异常是 {@link UserActionRecorder} 的职责，且只吞一次。
 * 如果这里也包一层 try/catch，「埋点为什么没写进去」就同时有两个可能的沉默地点，
 * 排查时得先读两个类才知道哪一个在撒谎。</p>
 *
 * <p>端口里的 {@code upsert(UserAction)} 收实体、发的是 {@link UserActionMapper#upsert} 的十个散列参数，
 * 这个「拆包」放在适配器而不是端口里，是因为端口要能被内存假实现轻松满足：
 * 假实现拿一个实体对象比对断言，比拿十个位置参数比对清楚得多</p>
 */
@Component
public class UserActionStoreAdapter implements UserActionRecorder.Store {

  private final UserActionMapper userActionMapper;
  private final EmotionRecordMapper emotionRecordMapper;

  public UserActionStoreAdapter(UserActionMapper userActionMapper,
      EmotionRecordMapper emotionRecordMapper) {
    this.userActionMapper = userActionMapper;
    this.emotionRecordMapper = emotionRecordMapper;
  }

  /**
   * 拆成裸 SQL 的十个参数。{@code id} 与 {@code createdAt} 不传——
   * 前者由自增决定，后者交给列上的 DEFAULT，这样「撞键时不覆盖 created_at」这条规则
   * 是由 SQL 里不出现它来保证的，而不是靠 Java 侧记得传 null。</p>
   */
  @Override
  public int upsert(UserAction action) {
    return userActionMapper.upsert(action.getUserId(), action.getTargetType(),
        action.getTargetId(), action.getActionType(), action.getWeight(), action.getMoodValence(),
        action.getMessageId(), action.getDayBucket(), action.getScene(), action.getDurationMs());
  }

  @Override
  public int cancelActive(long userId, String targetType, long targetId, String actionType) {
    return userActionMapper.cancelActive(userId, targetType, targetId, actionType);
  }

  /**
   * 读的是 emotion_record，见 {@link EmotionRecordMapper#latestCheckinMood}。
   *
   * <p>这里刻意不做「查不到就返回 0」的兜底：0 在需求 §8.2.2 里是一个真实的读数
   * （中性心情），把「没打卡」说成「心情中性」，情绪感知推荐会在一整天里没有打卡的用户身上
   * 稳定地推「中性内容」，而这条偏差在指标上看不出来。</p>
   */
  @Override
  public Integer moodValenceOf(long userId, LocalDate day) {
    return emotionRecordMapper.latestCheckinMood(userId, day);
  }
}
