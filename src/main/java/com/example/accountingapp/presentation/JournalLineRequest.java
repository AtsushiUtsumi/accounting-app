package com.example.accountingapp.presentation;

import com.example.accounting.model.account.Side;
import com.example.accountingapp.application.JournalLineCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** 仕訳の1行の入力。形式だけを検査し、貸借の一致などの会計ルールはドメインが検査する。 */
public record JournalLineRequest(@NotBlank String accountCode, @NotNull Side side, @Positive long amount) {

    public JournalLineCommand toCommand() {
        return new JournalLineCommand(accountCode, side, amount);
    }

    public static List<JournalLineCommand> toCommands(List<JournalLineRequest> lines) {
        return lines == null ? List.of() : lines.stream().map(JournalLineRequest::toCommand).toList();
    }
}
