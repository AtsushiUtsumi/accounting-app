package com.example.accountingapp.application.master;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCategory;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.account.AccountRepository;
import com.example.accounting.model.account.AccountTag;
import com.example.accounting.model.account.AccountType;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.JournalQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 科目マスタ(CLAUDE.md §6.8)。追加・名前の変更・タグの変更・無効化。標準科目の保護はドメインが担う。
 * 科目は削除しない(削除の操作は持たない)。
 */
@Service
public class AccountManagementUseCase {

    private final AccountRepository accountRepository;
    private final JournalQuery journalQuery;

    public AccountManagementUseCase(AccountRepository accountRepository, JournalQuery journalQuery) {
        this.accountRepository = accountRepository;
        this.journalQuery = journalQuery;
    }

    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accountRepository.findAll().stream()
                .sorted(Comparator.comparing(account -> account.code().value()))
                .map(account -> new AccountView(account, journalQuery.isAccountUsed(account.code())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountView get(String code) {
        Account account = find(code);
        return new AccountView(account, journalQuery.isAccountUsed(account.code()));
    }

    @Transactional
    public Account add(String code, String name, AccountType type, AccountCategory category, Set<String> tags) {
        AccountCode accountCode = AccountCode.of(code);
        if (accountRepository.existsByCode(accountCode)) {
            throw new OperationNotAllowedException("account already exists: " + code);
        }
        Account account = Account.of(accountCode, name, type, category).withTags(toTags(tags));
        accountRepository.save(account);
        return account;
    }

    /** 名前・タグの変更と無効化。null の項目は変更しない。 */
    @Transactional
    public Account update(String code, String newName, Set<String> newTags, Boolean deactivate) {
        Account account = find(code);
        if (newName != null) {
            account = account.rename(newName);
        }
        if (newTags != null) {
            account = account.withTags(toTags(newTags));
        }
        if (Boolean.TRUE.equals(deactivate)) {
            account = account.deactivate();
        }
        accountRepository.save(account);
        return account;
    }

    private Account find(String code) {
        return accountRepository.findByCode(AccountCode.of(code))
                .orElseThrow(() -> new ResourceNotFoundException("account not found: " + code));
    }

    private static Set<AccountTag> toTags(Set<String> tags) {
        return tags == null ? Set.of() : tags.stream().map(AccountTag::of).collect(Collectors.toSet());
    }

    /** 科目と、その科目を使った仕訳があるかどうか。 */
    public record AccountView(Account account, boolean used) {
    }
}
