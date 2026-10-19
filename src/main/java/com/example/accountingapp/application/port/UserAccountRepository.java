package com.example.accountingapp.application.port;

import com.example.accountingapp.application.user.UserAccount;

import java.util.List;
import java.util.Optional;

/** サインアップしたユーザーの保存先。設定ファイルのユーザーは {@link ConfiguredUsers} が持つ。 */
public interface UserAccountRepository {

    Optional<UserAccount> findByName(String name);

    /** すべてのユーザー(名前の順)。 */
    List<UserAccount> findAll();

    /** 新規なら追加し、既存ならロールを更新する。同じ名前のユーザーを同時に追加したら {@code DuplicateKeyException}。 */
    void save(UserAccount user);

    void delete(String name);
}
