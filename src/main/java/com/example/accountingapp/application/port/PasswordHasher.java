package com.example.accountingapp.application.port;

/** パスワードをハッシュにする(エンコード方式の接頭辞付き)。平文のパスワードは保存しない。 */
public interface PasswordHasher {

    String hash(String rawPassword);
}
