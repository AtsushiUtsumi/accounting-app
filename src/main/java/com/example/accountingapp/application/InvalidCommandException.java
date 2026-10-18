package com.example.accountingapp.application;

/** 入力の形式エラー(受け付けない仕訳の種類など)。会計ルールの違反ではない。HTTP 400 に変換する。 */
public class InvalidCommandException extends RuntimeException {

    public InvalidCommandException(String message) {
        super(message);
    }
}
