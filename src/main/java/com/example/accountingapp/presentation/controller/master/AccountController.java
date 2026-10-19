package com.example.accountingapp.presentation.controller.master;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCategory;
import com.example.accounting.model.account.AccountTag;
import com.example.accounting.model.account.AccountType;
import com.example.accountingapp.application.master.AccountManagementUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 科目マスタ。削除の API はない(科目は無効化する)。 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountManagementUseCase useCase;

    public AccountController(AccountManagementUseCase useCase) {
        this.useCase = useCase;
    }

    public record AccountResponse(String code, String name, AccountType type, AccountCategory category,
                                  Set<String> tags, boolean standard, boolean active, boolean used) {

        public static AccountResponse of(Account account, boolean used) {
            return new AccountResponse(account.code().value(), account.name(), account.type(), account.category(),
                    account.tags().stream().map(AccountTag::value).collect(java.util.stream.Collectors.toSet()),
                    account.standard(), account.active(), used);
        }
    }

    @GetMapping
    public List<AccountResponse> list() {
        return useCase.list().stream().map(view -> AccountResponse.of(view.account(), view.used())).toList();
    }

    @GetMapping("/{code}")
    public AccountResponse get(@PathVariable String code) {
        AccountManagementUseCase.AccountView view = useCase.get(code);
        return AccountResponse.of(view.account(), view.used());
    }

    public record AddAccountRequest(@NotBlank @Size(max = 16) String code, @NotBlank @Size(max = 100) String name,
                                    @NotNull AccountType type, @NotNull AccountCategory category, Set<String> tags) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse add(@Valid @RequestBody AddAccountRequest request) {
        Account account = useCase.add(request.code(), request.name(), request.type(), request.category(), request.tags());
        return AccountResponse.of(account, false);
    }

    /** 名前・タグの変更と無効化。省略した項目は変更しない。 */
    public record UpdateAccountRequest(@Size(max = 100) String name, Set<String> tags, Boolean deactivate) {
    }

    @PatchMapping("/{code}")
    public AccountResponse update(@PathVariable String code, @Valid @RequestBody UpdateAccountRequest request) {
        useCase.update(code, request.name(), request.tags(), request.deactivate());
        return get(code);
    }
}
