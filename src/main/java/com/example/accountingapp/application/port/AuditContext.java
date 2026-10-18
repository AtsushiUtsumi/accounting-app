package com.example.accountingapp.application.port;

import java.util.function.Supplier;

/**
 * いま実行中のユースケースの名前。仕訳を保存するときに監査列({@code journal.created_by_use_case})へ
 * 記録し、決算整理が作った仕訳と手入力の仕訳を区別するのに使う。ドメインの {@code Journal} には含めない。
 */
public final class AuditContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AuditContext() {
    }

    public static <T> T within(String useCase, Supplier<T> action) {
        String previous = CURRENT.get();
        CURRENT.set(useCase);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 実行中のユースケースの名前。ユースケースの外から保存したときは "UNKNOWN"。 */
    public static String currentUseCase() {
        String current = CURRENT.get();
        return current == null ? "UNKNOWN" : current;
    }
}
