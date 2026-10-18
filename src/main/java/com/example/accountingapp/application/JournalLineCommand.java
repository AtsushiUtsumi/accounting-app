package com.example.accountingapp.application;

import com.example.accounting.model.account.Side;

/** 仕訳の1行の入力(科目コード、借方/貸方、金額(円))。 */
public record JournalLineCommand(String accountCode, Side side, long amount) {
}
