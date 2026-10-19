package com.example.accountingapp.application.port;

import com.example.accountingapp.application.user.UserAccount;

import java.util.List;

/** 設定ファイル({@code accounting.security.users})のユーザー。アプリからは変更・削除できない。 */
public interface ConfiguredUsers {

    List<UserAccount> all();
}
