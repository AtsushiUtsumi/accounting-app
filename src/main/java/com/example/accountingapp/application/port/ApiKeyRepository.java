package com.example.accountingapp.application.port;

import com.example.accountingapp.application.user.ApiKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** MCP 用の API キーの保存先。キーそのものではなく、ハッシュを保存する。 */
public interface ApiKeyRepository {

    Optional<ApiKey> findByTokenHash(String tokenHash);

    /** そのユーザーのキー(発行した順)。 */
    List<ApiKey> findByUser(String userName);

    void add(ApiKey key);

    void markUsed(UUID id, Instant usedAt);

    /** そのユーザーのキーを1つ削除する。削除できたら true。 */
    boolean delete(String userName, UUID id);

    void deleteByUser(String userName);
}
