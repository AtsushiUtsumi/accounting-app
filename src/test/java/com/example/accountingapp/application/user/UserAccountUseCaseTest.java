package com.example.accountingapp.application.user;

import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.port.UserAccountRepository;
import com.example.accountingapp.application.user.UserAccount.Role;
import com.example.accountingapp.application.user.UserAccount.Source;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 最後の承認者を守るルール(承認者がいなくなると、だれもロールを付けられなくなる)。 */
class UserAccountUseCaseTest {

    private final Map<String, UserAccount> stored = new LinkedHashMap<>();
    private final List<UserAccount> configured = new ArrayList<>();

    private final UserAccountRepository repository = new UserAccountRepository() {
        @Override
        public Optional<UserAccount> findByName(String name) {
            return Optional.ofNullable(stored.get(name));
        }

        @Override
        public List<UserAccount> findAll() {
            return stored.values().stream().sorted(Comparator.comparing(UserAccount::name)).toList();
        }

        @Override
        public void save(UserAccount user) {
            stored.put(user.name(), user);
        }

        @Override
        public void delete(String name) {
            stored.remove(name);
        }
    };

    private final UserAccountUseCase useCase = new UserAccountUseCase(repository, () -> configured, raw -> "{test}" + raw);

    @Test
    void theLastApproverCannotBeDeletedOrDemoted() {
        useCase.signUp("alice", "password-1");
        useCase.changeRole("alice", Role.APPROVER);

        assertThatThrownBy(() -> useCase.delete("alice")).isInstanceOf(OperationNotAllowedException.class);
        assertThatThrownBy(() -> useCase.changeRole("alice", Role.VIEWER)).isInstanceOf(OperationNotAllowedException.class);

        useCase.signUp("bob", "password-2");
        useCase.changeRole("bob", Role.APPROVER);
        useCase.delete("alice");
        assertThat(useCase.list()).extracting(UserAccount::name).containsExactly("bob");
    }

    @Test
    void configuredApproversCountAsApprovers() {
        configured.add(new UserAccount("admin", "{noop}admin", Role.APPROVER, Source.CONFIGURED));
        useCase.signUp("alice", "password-1");
        useCase.changeRole("alice", Role.APPROVER);

        useCase.delete("alice");

        assertThat(stored).isEmpty();
        assertThatThrownBy(() -> useCase.delete("admin")).isInstanceOf(OperationNotAllowedException.class);
    }

    @Test
    void signUpStoresTheHashAndNoRole() {
        UserAccount user = useCase.signUp("alice", "password-1");

        assertThat(user.role()).isNull();
        assertThat(stored.get("alice").passwordHash()).isEqualTo("{test}password-1");
    }
}
