package com.example.accountingapp.presentation;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.money.Money;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 科目と金額(円)。ドメインの {@code Map<Account, Money>} を JSON にするときに使う。
 * 金額は JSON の整数(円)でやり取りする。
 */
public record AccountAmount(String accountCode, String accountName, long amount) {

    public static AccountAmount of(Account account, Money amount) {
        return new AccountAmount(account.code().value(), account.name(), amount.amountInYen());
    }

    /** 科目コード順に並べる。 */
    public static List<AccountAmount> listOf(Map<Account, Money> amounts) {
        return amounts.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().code().value()))
                .map(entry -> of(entry.getKey(), entry.getValue()))
                .toList();
    }
}
