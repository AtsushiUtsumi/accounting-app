package com.example.accountingapp.support.inmemory;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.account.AccountRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryAccountRepository implements AccountRepository {

    private final Map<AccountCode, Account> accounts = new LinkedHashMap<>();

    @Override
    public Optional<Account> findByCode(AccountCode code) {
        return Optional.ofNullable(accounts.get(code));
    }

    @Override
    public List<Account> findAll() {
        return new ArrayList<>(accounts.values());
    }

    @Override
    public void save(Account account) {
        accounts.put(account.code(), account);
    }

    @Override
    public boolean existsByCode(AccountCode code) {
        return accounts.containsKey(code);
    }
}
