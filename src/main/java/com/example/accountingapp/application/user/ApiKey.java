package com.example.accountingapp.application.user;

import java.time.Instant;
import java.util.UUID;

/**
 * MCP 用の API キー。キーそのものは持たない(発行したときに一度だけ返す)。
 *
 * @param tokenHash キーの SHA-256(16進)
 * @param prefix    一覧で見分けるための、キーの先頭の数文字
 */
public record ApiKey(UUID id, String userName, String name, String tokenHash, String prefix, Instant createdAt,
                     Instant lastUsedAt) {
}
