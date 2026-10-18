package com.example.accountingapp.application.journal;

import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.service.ReversalService;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.port.AuditContext;
import com.example.accountingapp.application.port.JournalQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 仕訳を赤黒で訂正する(CLAUDE.md §6.2)。赤と黒は、訂正日を含む OPEN の期にまとめて記帳する。
 * 元の仕訳が締めた期のものでも、訂正は当期に記帳される。
 *
 * <p>同じ仕訳の二重取消は、ライブラリにルールがないため、アプリ側で {@link JournalQuery#isReversed} により防ぐ
 * (CLAUDE.md §12 の未決事項2。ライブラリ側のルールになったら、このチェックは外す)。
 */
@Service
public class CorrectJournalUseCase {

    public static final String NAME = "CorrectJournal";

    private final JournalBook book;
    private final JournalQuery journalQuery;
    private final ReversalService reversalService;
    private final PostJournalUseCase postJournalUseCase;

    public CorrectJournalUseCase(JournalBook book, JournalQuery journalQuery, ReversalService reversalService,
                                 PostJournalUseCase postJournalUseCase) {
        this.book = book;
        this.journalQuery = journalQuery;
        this.reversalService = reversalService;
        this.postJournalUseCase = postJournalUseCase;
    }

    @Transactional
    public Result correct(CorrectJournalCommand command) {
        return AuditContext.within(NAME, () -> {
            PostedJournal original = book.journal(command.originalId());
            if (journalQuery.isReversed(original.id())) {
                throw new OperationNotAllowedException("journal is already reversed: " + original.id().value());
            }

            List<Journal> journals = new ArrayList<>();
            Journal reversal = reversalService.reverse(JournalId.newId(), command.date(), original.journal());
            journals.add(reversal);
            JournalId correctId = null;
            if (command.correctJournal().isPresent()) {
                CorrectJournalCommand.CorrectedJournal correct = command.correctJournal().get();
                Journal journal = postJournalUseCase.draft(new PostJournalCommand(
                        command.date(), correct.description(), correct.type(), correct.lines()));
                journals.add(journal);
                correctId = journal.id();
            }

            AccountingPeriod period = book.periodContaining(command.date());
            book.postAllAndSave(period, journals);
            return new Result(reversal.id(), correctId);
        });
    }

    /** 取消仕訳(赤)の ID と、正しい仕訳(黒)の ID(取消のみなら null)。 */
    public record Result(JournalId reversalId, JournalId correctJournalId) {
    }
}
