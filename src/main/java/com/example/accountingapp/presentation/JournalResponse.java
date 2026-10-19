package com.example.accountingapp.presentation;

import com.example.accounting.model.account.Side;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.journal.PostedJournal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 仕訳のレスポンス。ドメインの record をそのまま JSON にせず、ここで詰め替える。
 * 記帳前の仕訳(プレビュー)では {@code periodId} は null。{@code reversed} は赤黒で取り消し済みか。
 */
public record JournalResponse(UUID id, UUID periodId, LocalDate date, String description, JournalType type,
                              UUID reversalOf, boolean reversed, List<Line> lines) {

    public record Line(String accountCode, String accountName, Side side, long amount) {

        static Line of(JournalLine line) {
            return new Line(line.account().code().value(), line.account().name(), line.side(),
                    line.amount().amountInYen());
        }
    }

    public static JournalResponse of(PostedJournal posted, boolean reversed) {
        return of(posted.journal(), posted.periodId().value(), reversed);
    }

    public static JournalResponse of(Journal journal) {
        return of(journal, null, false);
    }

    private static JournalResponse of(Journal journal, UUID periodId, boolean reversed) {
        return new JournalResponse(
                journal.id().value(), periodId, journal.date(), journal.description(), journal.type(),
                journal.reversalOf() == null ? null : journal.reversalOf().value(), reversed,
                journal.lines().stream().map(Line::of).toList());
    }
}
