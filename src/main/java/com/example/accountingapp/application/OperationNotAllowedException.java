package com.example.accountingapp.application;

/**
 * アプリの状態から見て、いま実行できない操作(決算整理の二重実行、同じ仕訳の二重取消、
 * 会社の二重初期化など)。会計ルールの違反(ドメインの {@code AccountingException})とは区別する。
 * HTTP 409 に変換する。
 */
public class OperationNotAllowedException extends RuntimeException {

    public OperationNotAllowedException(String message) {
        super(message);
    }
}
