package com.mindisle.privacy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mindisle.privacy.PrivacyDomains.Action;
import com.mindisle.privacy.PrivacyDomains.Domain;
import com.mindisle.privacy.PrivacyDomains.Link;

/**
 * {@link PrivacyStore} 的真库实现（任务 T4.21）。全部 34 张表的按人读写都由这一个类拼出来，
 * 而拼的规则只有一条：<b>形状来自 {@link PrivacyDomains} 注册表，值一律走占位符</b>。
 *
 * <p><b>依赖核查（先写在这里，免得下一轮再查一遍）</b>：本类用
 * {@link JdbcTemplate}，而项目里没有显式引入 {@code spring-boot-starter-jdbc}。它可用是因为
 * {@code mybatis-plus-spring-boot4-starter} 的 pom 把 {@code spring-boot-starter-jdbc} 列为
 * 直接依赖，jar 已落在本地仓库（{@code spring-jdbc-7.0.9.jar}）。<b>不为此新增任何坐标</b> ——
 * 加一个「看着更明确」的 starter 反而多一处版本要对齐的地方，而离线构建缺插件的教训已经吃过
 * （见 dev-log：{@code mvn package} 在离线环境直接不可用）。</p>
 *
 * <p><b>为什么标识符只能白名单、不能参数化</b>：SQL 注入的标准解法是「值全部进
 * {@code ?}」，但表名与列名<b>不是值</b>，占位符放不进去。所以这里把风险拆成两半处理：
 * ① 值（user id、post id 集合、哨兵 0、limit）100% 是 {@code ?}；
 * ② 形状（表名、列名）100% 来自 {@link PrivacyDomains#ALL} 这张<b>写死在源码里的</b>注册表，
 * 且每个标识符在拼进 SQL 之前都要过 {@link #IDENTIFIER} 白名单。
 * 结论：没有任何一条 SQL 的形状能被调用方影响 —— 调用方能影响的只有 {@code ?} 后面的值。
 * 这条不变量是整个隐私中心最该被复审的地方，所以它写在类注释而不是方法注释里。</p>
 *
 * <p><b>为什么这里不加 {@code deleted = 0}</b>（与全站读侧相反，三处都提醒一遍）：
 * {@code @TableLogic} 只改写 MyBatis-Plus 自己的语句，管不到这里的原生 SQL；而这里
 * <b>故意</b>不要它 —— 导出要把软删行一起给用户（那是他的数据），
 * 物理清除更要把软删行真的删掉（留着就等于没行使删除权，{@code deleted = 1} 只是「看不见」）。
 * 需求 D11 的「导出条数 = 库内条数」这条对账也只有在不过滤软删时才成立。
 * 反过来，读侧（广场/搜索）过滤软删是对的，两处口径不同不是因为不一致，
 * 而是因为一个在回答「别人能看见什么」、另一个在回答「哪些行属于我」。</p>
 */
@Repository
public class JdbcPrivacyStore implements PrivacyStore {

  private static final Logger log = LoggerFactory.getLogger(JdbcPrivacyStore.class);

  /**
   * 标识符白名单：只允许小写字母开头的 {@code [a-z0-9_]}。注册表里 34 张表与所有列名都满足它
   * （{@code PrivacyDomainsTest} 逐条钉过），所以正常路径永不误伤；
   * 一旦有人往注册表里塞了带空格、引号或反引号的名字，这里当场抛，而不是让它进 SQL。
   */
  private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

  /**
   * MySQL 的标识符引用符。取 {@code (char) 96} 而不是裸写字面量：这一字符在 Java 里是
   * {@code BACKTICK}、在脚本工具里又要拼进字符串，写成裸字符字面量很容易被下游工具吃掉
   * （本轮真实踩过，见 dev-log 的工具层记录）。常量 + 注释比一个裸符号更好读。
   */
  private static final char BACKTICK = (char) 96;

  /**
   * 「归属集合为空」时的谓词。<b>绝不退化成空条件</b>：
   * 一个没有任何帖子的用户走到 {@code DELETE FROM post WHERE } 后面缺东西，就是全表删除。
   * {@code 1 = 0} 让这种场景最多删 0 行。
   */
  private static final String NEVER = "1 = 0";

  /**
   * post 主行集合。<b>不带 {@code deleted}、不带 {@code status}</b>，理由见类注释第三段：
   * 软删帖与待审帖同样是本人数据，都要进导出包、都要被清除。
   * {@code ORDER BY id ASC} 是为了让导出的行序稳定 —— 同一份数据两次导出应当逐字节可比。
   */
  private static final String POST_IDS_SQL = "SELECT id FROM post WHERE user_id = ? ORDER BY id ASC";

  private final JdbcTemplate jdbc;

  /**
   * 注入 {@link DataSource} 而不是 {@link JdbcTemplate}：容器里没有现成的 JdbcTemplate bean
   * （数据源由 mybatis-plus starter 装配），自己 new 一个只包住同一个 DataSource，
   * 不会多出第二个连接池。
   */
  public JdbcPrivacyStore(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  @Override
  public List<Long> postIdsOf(long userId) {
    return jdbc.queryForList(POST_IDS_SQL, Long.class, userId);
  }

  /**
   * {@code SELECT *} 是刻意的：注册表只管归属，不管列。
   * 把列清单抄在这里等于再维护一份真值，DDL 改一列就得改两个地方 —— 而这一层出错的方式
   * 是「导出包里少了一列」，没人会去断言它。列名到值的映射交给驱动，截断判断交给调用方
   * （按「返回行数 == limit」），这里只负责「按注册表把这个人的一批行取回来」。
   */
  @Override
  public List<Map<String, Object>> selectRows(Domain domain, long userId, List<Long> postIds, int limit) {
    List<Object> args = new ArrayList<>();
    String where = where(domain, userId, postIds, args);
    args.add(Math.max(1, limit));
    return jdbc.queryForList(
        "SELECT * FROM " + quote(domain.table()) + " WHERE " + where + " LIMIT ?", args.toArray());
  }

  /** 与 {@link #selectRows} 共用同一条 {@link #where} —— D11 的对账能成立就靠这一点。 */
  @Override
  public long countRows(Domain domain, long userId, List<Long> postIds) {
    List<Object> args = new ArrayList<>();
    String where = where(domain, userId, postIds, args);
    Long n = jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + quote(domain.table()) + " WHERE " + where, Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public int deleteRows(Domain domain, long userId, List<Long> postIds) {
    List<Object> args = new ArrayList<>();
    String where = where(domain, userId, postIds, args);
    if (NEVER.equals(where)) {
      return 0;
    }
    int n = jdbc.update("DELETE FROM " + quote(domain.table()) + " WHERE " + where, args.toArray());
    if (n > 0) {
      log.info("物理清除 table={} user={} rows={}", domain.table(), userId, n);
    }
    return n;
  }

  /**
   * 解绑身份：{@code SET col = 0, ... WHERE <同一条归属谓词>}。
   *
   * <p>WHERE 用 {@code List.of()} 作 postIds，因为三个解绑域在注册表里全是 {@code Link.USER}。
   * 万一将来有人给 {@code Link.POST} 的表登记了 {@code unbindCols}，这里会拿到 {@link #NEVER}
   * 而更新 0 行 —— 宁可无声地什么都不改，也不改别人的行（那条表本来就该改成 DELETE 或补 user 列）。</p>
   */
  @Override
  public int unbind(Domain domain, long userId) {
    List<String> cols = domain.unbindCols();
    if (domain.action() != Action.UNBIND || cols.isEmpty()) {
      throw new IllegalStateException("注册表里这一项不是解绑域，拒绝执行 unbind：" + domain.table());
    }
    List<Object> whereArgs = new ArrayList<>();
    String where = where(domain, userId, List.of(), whereArgs);
    if (NEVER.equals(where)) {
      return 0;
    }
    List<Object> args = new ArrayList<>();
    StringBuilder sql = new StringBuilder("UPDATE ").append(quote(domain.table())).append(" SET ");
    for (int i = 0; i < cols.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(quote(cols.get(i))).append(" = ?");
      args.add(PrivacyDomains.UNBOUND);
    }
    sql.append(" WHERE ").append(where);
    args.addAll(whereArgs);
    int n = jdbc.update(sql.toString(), args.toArray());
    if (n > 0) {
      log.info("身份解绑 table={} user={} cols={} rows={}", domain.table(), userId, cols, n);
    }
    return n;
  }

  /**
   * 按归属方式拼谓词。三种 {@link Link} 各一条分支，值全部进 {@code args}。
   *
   * <p>用 if/else 而不是 switch：{@code Link} 是这里定义的枚举，switch 的「漏一个 case」在编译期
   * 不会报错（除非额外写 default 抛错，那就和 if/else 等长）；而 if/else 的最后一档
   * 直接落到 USER 分支的判断上，读的人一眼能看到「NONE 必须先被拦住」。</p>
   */
  private static String where(Domain domain, long userId, List<Long> postIds, List<Object> args) {
    Link link = domain.link();
    if (link == Link.NONE) {
      throw new IllegalStateException("全局表按人读写没有意义，注册表里它不该被走到：" + domain.table());
    }
    if (link == Link.POST) {
      String postCol = domain.postCol();
      if (postCol == null) {
        throw new IllegalStateException("注册表里 link=POST 却没写 postCol：" + domain.table());
      }
      if (postIds == null || postIds.isEmpty()) {
        return NEVER;
      }
      StringBuilder sql = new StringBuilder(quote(postCol)).append(" IN (");
      for (int i = 0; i < postIds.size(); i++) {
        if (i > 0) {
          sql.append(", ");
        }
        sql.append("?");
        args.add(postIds.get(i));
      }
      return sql.append(")").toString();
    }
    List<String> cols = domain.userCols();
    if (cols.isEmpty()) {
      throw new IllegalStateException("注册表里 link=USER 却没写 userCols：" + domain.table());
    }
    if (cols.size() == 1) {
      args.add(userId);
      return quote(cols.get(0)) + " = ?";
    }
    StringBuilder sql = new StringBuilder("(");
    for (int i = 0; i < cols.size(); i++) {
      if (i > 0) {
        sql.append(" OR ");
      }
      sql.append(quote(cols.get(i))).append(" = ?");
      args.add(userId);
    }
    return sql.append(")").toString();
  }

  /** 白名单不过就抛，绝不「姑且拼上去」。见类注释第 2 条不变量。 */
  private static String quote(String identifier) {
    if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
      throw new IllegalStateException("注册表里的标识符不合法，拒绝拼进 SQL：" + identifier);
    }
    return "" + BACKTICK + identifier + BACKTICK;
  }
}
