package com.example.accountingapp.application.user;

import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.ConfiguredUsers;
import com.example.accountingapp.application.port.PasswordHasher;
import com.example.accountingapp.application.port.UserAccountRepository;
import com.example.accountingapp.application.user.UserAccount.Role;
import com.example.accountingapp.application.user.UserAccount.Source;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ユーザーのサインアップ・ロールの付与・削除(CLAUDE.md §9)。
 *
 * <p>だれでもサインアップできるが、サインアップした直後のユーザーにはロールがなく、帳簿を見ることも記帳することもできない。
 * 承認者がロールを付ける。設定ファイルのユーザーはアプリからは変更・削除できない。
 * 承認者がいなくなるとロールを付けられなくなるため、最後の承認者は削除もロールの変更もできない。
 */
@Service
public class UserAccountUseCase {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private static final int MIN_PASSWORD_LENGTH = 8;
    /** bcrypt はこれより長いパスワードを切り捨てる。 */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserAccountRepository repository;
    private final ConfiguredUsers configuredUsers;
    private final PasswordHasher passwordHasher;

    public UserAccountUseCase(UserAccountRepository repository, ConfiguredUsers configuredUsers,
                              PasswordHasher passwordHasher) {
        this.repository = repository;
        this.configuredUsers = configuredUsers;
        this.passwordHasher = passwordHasher;
    }

    @Transactional
    public UserAccount signUp(String name, String password) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new InvalidCommandException("user name must be 3-50 characters of letters, digits, '.', '_' or '-'");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidCommandException("password must be at least " + MIN_PASSWORD_LENGTH
                    + " characters and at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (find(name).isPresent()) {
            throw new OperationNotAllowedException("user name is already taken: " + name);
        }
        UserAccount user = new UserAccount(name, passwordHasher.hash(password), null, Source.SIGNED_UP);
        try {
            repository.save(user);
        } catch (DuplicateKeyException e) {
            throw new OperationNotAllowedException("user name is already taken: " + name);
        }
        return user;
    }

    /** 設定ファイルのユーザーとサインアップしたユーザー(名前の順)。 */
    @Transactional(readOnly = true)
    public List<UserAccount> list() {
        List<UserAccount> users = new ArrayList<>(configuredUsers.all());
        users.addAll(repository.findAll());
        users.sort(Comparator.comparing(UserAccount::name));
        return users;
    }

    /** ロールを付ける・変える・外す(null)。 */
    @Transactional
    public UserAccount changeRole(String name, Role role) {
        UserAccount user = modifiable(name);
        if (user.role() == Role.APPROVER && role != Role.APPROVER) {
            requireAnotherApprover(user);
        }
        UserAccount changed = user.withRole(role);
        repository.save(changed);
        return changed;
    }

    @Transactional
    public void delete(String name) {
        UserAccount user = modifiable(name);
        if (user.role() == Role.APPROVER) {
            requireAnotherApprover(user);
        }
        repository.delete(user.name());
    }

    private UserAccount modifiable(String name) {
        UserAccount user = find(name).orElseThrow(() -> new ResourceNotFoundException("user not found: " + name));
        if (user.source() == Source.CONFIGURED) {
            throw new OperationNotAllowedException(
                    "users in the configuration file cannot be changed or deleted in the app: " + name);
        }
        return user;
    }

    private void requireAnotherApprover(UserAccount user) {
        boolean another = list().stream()
                .anyMatch(other -> other.role() == Role.APPROVER && !other.name().equals(user.name()));
        if (!another) {
            throw new OperationNotAllowedException("the last approver cannot be deleted or demoted: " + user.name());
        }
    }

    private Optional<UserAccount> find(String name) {
        return configuredUsers.all().stream()
                .filter(user -> user.name().equals(name))
                .findFirst()
                .or(() -> repository.findByName(name));
    }
}
