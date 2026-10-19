package com.example.accountingapp.infrastructure.persistence;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;

import java.util.Map;

/** 外部キーのない列(連結の資料)に保存した科目コードを、科目表の {@link Account} に戻す。 */
final class PersistedAccounts {

    private PersistedAccounts() {
    }

    static Account get(Map<AccountCode, Account> accounts, String code) {
        Account account = accounts.get(AccountCode.of(code));
        if (account == null) {
            throw new IllegalStateException("account in consolidation material is missing from the chart: " + code);
        }
        return account;
    }
}
