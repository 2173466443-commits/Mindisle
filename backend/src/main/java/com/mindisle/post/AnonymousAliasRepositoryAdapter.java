package com.mindisle.post;

import java.util.List;

import com.mindisle.entity.AnonymousAlias;
import com.mindisle.mapper.AnonymousAliasMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 马甲存储端口的 MyBatis 适配器（任务 3.4）。
 *
 * <p>表还不存在时的行为与全站一致：抛 DataAccessException，由 GlobalExceptionHandler
 * 统一转成 90002 / HTTP 503。这里刻意不做「查不到就返回 null」的静默兜底——
 * 马甲没落库却给用户一个昵称，等于造出一个换了进程就消失的假身份。</p>
 */
@Component
public class AnonymousAliasRepositoryAdapter implements AnonymousAliasRepository {

    private static final Logger log = LoggerFactory.getLogger(AnonymousAliasRepositoryAdapter.class);

    private final AnonymousAliasMapper mapper;

    public AnonymousAliasRepositoryAdapter(AnonymousAliasMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AnonymousAlias findByUserAndScene(long userId, String scene) {
        return mapper.findByUserAndScene(userId, scene);
    }

    @Override
    public List<AnonymousAlias> listByUser(long userId) {
        List<AnonymousAlias> rows = mapper.listByUser(userId);
        return rows == null ? List.of() : rows;
    }

    @Override
    public String insertIfAbsent(long userId, String scene, String aliasName) {
        AnonymousAlias row = new AnonymousAlias();
        row.setUserId(userId);
        row.setScene(scene);
        row.setAliasName(aliasName);
        try {
            mapper.insert(row);
            return aliasName;
        } catch (DuplicateKeyException e) {
            AnonymousAlias winner = mapper.findByUserAndScene(userId, scene);
            log.info("马甲并发插入撞唯一键，改用已存在的别名：user={} scene={}", userId, scene);
            return winner == null ? aliasName : winner.getAliasName();
        }
    }
}
