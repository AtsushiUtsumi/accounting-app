package com.example.accountingapp.application.user;

/**
 * アプリのユーザー(会計の「勘定科目」とは別物)。
 *
 * @param passwordHash エンコード方式の接頭辞付きのハッシュ(例: {@code {bcrypt}$2a$...})
 * @param role         ロール。サインアップした直後は null(承認者が付けるまで、どの帳簿も見られない)
 * @param source       設定ファイルのユーザーか、サインアップしたユーザーか
 */
public record UserAccount(String name, String passwordHash, Role role, Source source) {

    public enum Role { VIEWER, BOOKKEEPER, APPROVER }

    public enum Source {
        /** 設定ファイル({@code accounting.security.users})のユーザー。アプリからは変更・削除できない。 */
        CONFIGURED,
        /** サインアップしたユーザー。DB に保存する。 */
        SIGNED_UP
    }

    public UserAccount withRole(Role newRole) {
        return new UserAccount(name, passwordHash, newRole, source);
    }
}
