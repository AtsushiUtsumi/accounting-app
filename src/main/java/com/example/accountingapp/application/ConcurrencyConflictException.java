package com.example.accountingapp.application;

/**
 * 同時実行による競合。会計期間の楽観ロックの失敗や、{@code saveAll} の version 不一致
 * (記帳の後に期が締められた)など。HTTP 409 に変換する。
 */
public class ConcurrencyConflictException extends RuntimeException {

    public ConcurrencyConflictException(String message) {
        super(message);
    }

    public ConcurrencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
