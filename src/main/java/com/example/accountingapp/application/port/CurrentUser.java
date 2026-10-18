package com.example.accountingapp.application.port;

/** 操作しているユーザー。監査列 {@code journal.created_by} に記録する。 */
public interface CurrentUser {

    String name();
}
