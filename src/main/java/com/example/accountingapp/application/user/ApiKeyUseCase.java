package com.example.accountingapp.application.user;

import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 用の API キー(CLAUDE.md §9)。ユーザーが自分のキーを発行・失効させる。
 *
 * <p>キーは 256 ビットの乱数で、DB には SHA-256 のハッシュだけを保存する(推測できない長さなので、パスワードのような
 * 遅いハッシュは要らない)。キーそのものは発行したときに一度だけ返す。キーを使ったときの権限は、持ち主の
 * その時点のロール(ロールを外されたり、アカウントを削除されたりすれば、キーでも使えなくなる)。
 */
@Service
public class ApiKeyUseCase {

    /** キーの先頭に付ける目印(漏れたときに、何のキーか分かるように)。 */
    static final String TOKEN_PREFIX = "acc_";
    private static final int MAX_KEYS_PER_USER = 20;
    private static final int PREFIX_LENGTH = TOKEN_PREFIX.length() + 6;

    private final ApiKeyRepository repository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyUseCase(ApiKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 発行したキー。{@code token} はこのときにしか取り出せない。 */
    public record Issued(ApiKey key, String token) {
    }

    @Transactional
    public Issued issue(String userName, String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new InvalidCommandException("API key name is required (at most 100 characters)");
        }
        if (repository.findByUser(userName).size() >= MAX_KEYS_PER_USER) {
            throw new OperationNotAllowedException("a user can have at most " + MAX_KEYS_PER_USER + " API keys");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ApiKey key = new ApiKey(UUID.randomUUID(), userName, name.strip(), hash(token),
                token.substring(0, PREFIX_LENGTH), clock.instant(), null);
        repository.add(key);
        return new Issued(key, token);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(String userName) {
        return repository.findByUser(userName);
    }

    @Transactional
    public void revoke(String userName, UUID id) {
        if (!repository.delete(userName, id)) {
            throw new ResourceNotFoundException("API key not found: " + id);
        }
    }

    /** キーの持ち主のユーザー名。キーが正しくなければ空。使った日時を記録する。 */
    @Transactional
    public Optional<String> authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiKey> key = repository.findByTokenHash(hash(token));
        key.ifPresent(found -> repository.markUsed(found.id(), clock.instant()));
        return key.map(ApiKey::userName);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
