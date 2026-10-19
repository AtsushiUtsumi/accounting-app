package com.example.accountingapp.infrastructure.persistence;

import com.example.accountingapp.application.port.ApiKeyRepository;
import com.example.accountingapp.application.user.ApiKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MCP 用の API キー(ハッシュだけを保存する)。 */
@Repository
public class JdbcApiKeyRepository implements ApiKeyRepository {

    private final JdbcClient jdbc;

    public JdbcApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiKey> findByTokenHash(String tokenHash) {
        return jdbc.sql("select * from api_key where token_hash = ?")
                .param(tokenHash)
                .query((rs, n) -> toKey(rs))
                .optional();
    }

    @Override
    public List<ApiKey> findByUser(String userName) {
        return jdbc.sql("select * from api_key where user_name = ? order by created_at, id")
                .param(userName)
                .query((rs, n) -> toKey(rs))
                .list();
    }

    @Override
    public void add(ApiKey key) {
        jdbc.sql("""
                        insert into api_key (id, user_name, name, token_hash, prefix, created_at)
                        values (?, ?, ?, ?, ?, ?)
                        """)
                .params(key.id(), key.userName(), key.name(), key.tokenHash(), key.prefix(),
                        OffsetDateTime.ofInstant(key.createdAt(), ZoneOffset.UTC))
                .update();
    }

    @Override
    public void markUsed(UUID id, Instant usedAt) {
        jdbc.sql("update api_key set last_used_at = ? where id = ?")
                .params(OffsetDateTime.ofInstant(usedAt, ZoneOffset.UTC), id)
                .update();
    }

    @Override
    public boolean delete(String userName, UUID id) {
        return jdbc.sql("delete from api_key where user_name = ? and id = ?").params(userName, id).update() > 0;
    }

    @Override
    public void deleteByUser(String userName) {
        jdbc.sql("delete from api_key where user_name = ?").param(userName).update();
    }

    private static ApiKey toKey(ResultSet rs) throws SQLException {
        Timestamp lastUsed = rs.getTimestamp("last_used_at");
        return new ApiKey(rs.getObject("id", UUID.class), rs.getString("user_name"), rs.getString("name"),
                rs.getString("token_hash"), rs.getString("prefix"), rs.getTimestamp("created_at").toInstant(),
                lastUsed == null ? null : lastUsed.toInstant());
    }
}
