package com.example.accountingapp.application.journal;

import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.port.AuditContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 仕訳を記帳する(CLAUDE.md §6.1)。貸借の不一致は {@link Journal#of}、CLOSED の期・期間外の日付は
 * {@code PostedJournal.post} が例外にする。
 */
@Service
public class PostJournalUseCase {

    public static final String NAME = "PostJournal";

    /** 手入力を受け付ける種類。ADJUSTING は、ライブラリが自動で作らない決算整理(経過勘定・貯蔵品)のため。 */
    private static final Set<JournalType> ACCEPTED_TYPES = Set.of(JournalType.NORMAL, JournalType.ADJUSTING);

    private final JournalBook book;
    private final AccountResolver accounts;

    public PostJournalUseCase(JournalBook book, AccountResolver accounts) {
        this.book = book;
        this.accounts = accounts;
    }

    @Transactional
    public JournalId post(PostJournalCommand command) {
        return AuditContext.within(NAME, () -> {
            AccountingPeriod period = book.periodContaining(command.date());
            Journal journal = draft(command);
            book.postAndSave(period, journal);
            return journal.id();
        });
    }

    /** 記帳前の仕訳を作る。赤黒訂正の黒にも使う。 */
    Journal draft(PostJournalCommand command) {
        if (!ACCEPTED_TYPES.contains(command.type())) {
            throw new InvalidCommandException("journal type must be NORMAL or ADJUSTING: " + command.type());
        }
        return Journal.of(JournalId.newId(), command.date(), command.description(), command.type(),
                accounts.toLines(command.lines()));
    }
}
