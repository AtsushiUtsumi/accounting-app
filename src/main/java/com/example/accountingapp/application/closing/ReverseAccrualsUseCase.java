package com.example.accountingapp.application.closing;

import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.service.AdjustingEntryService;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.AuditContext;
import com.example.accountingapp.application.port.JournalQuery;
import com.example.accountingapp.application.port.PeriodLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 再振替(CLAUDE.md §6.6)。次期に対して実行し、前期の ADJUSTING 仕訳のうち再振替の対象になるものを、
 * 次期の期首日付で記帳する。締めとは別の操作にする(§12 の未決事項3。どちらでも日付は次期の期首)。
 */
@Service
public class ReverseAccrualsUseCase {

    public static final String NAME = "ReverseAccruals";

    private final JournalBook book;
    private final PeriodLookup periodLookup;
    private final JournalQuery journalQuery;
    private final AdjustingEntryService adjustingEntryService;

    public ReverseAccrualsUseCase(JournalBook book, PeriodLookup periodLookup, JournalQuery journalQuery,
                                  AdjustingEntryService adjustingEntryService) {
        this.book = book;
        this.periodLookup = periodLookup;
        this.journalQuery = journalQuery;
        this.adjustingEntryService = adjustingEntryService;
    }

    @Transactional
    public List<JournalId> reverse(UUID periodId) {
        return AuditContext.within(NAME, () -> {
            AccountingPeriod period = book.period(periodId);
            AccountingPeriod previous = periodLookup.findPrevious(period)
                    .orElseThrow(() -> new ResourceNotFoundException("no previous period for " + periodId));
            if (previous.isOpen()) {
                throw new OperationNotAllowedException("the previous period must be closed before reversing accruals");
            }
            if (journalQuery.existsByPeriodAndType(period, JournalType.REVERSING)) {
                throw new OperationNotAllowedException("accruals have already been reversed in this period");
            }

            List<Journal> previousAdjusting = book.journalsOf(previous).stream()
                    .filter(journal -> journal.type() == JournalType.ADJUSTING)
                    .toList();
            List<Journal> reversing = adjustingEntryService.reverseAccruals(previousAdjusting, period.startDate());
            book.postAllAndSave(period, reversing);
            return reversing.stream().map(Journal::id).toList();
        });
    }
}
