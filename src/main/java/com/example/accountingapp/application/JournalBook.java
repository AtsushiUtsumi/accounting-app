package com.example.accountingapp.application;

import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalRepository;
import com.example.accounting.model.journal.PostedJournal;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accounting.model.period.AccountingPeriodId;
import com.example.accounting.model.period.AccountingPeriodRepository;
import com.example.accountingapp.application.port.PeriodLookup;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ユースケースに共通の「取得する → (ドメインに計算させる) → 記帳して保存する」のうち、取得と保存の部分。
 * 会計の計算はしない。
 */
@Component
public class JournalBook {

    private final AccountingPeriodRepository periodRepository;
    private final PeriodLookup periodLookup;
    private final JournalRepository journalRepository;

    public JournalBook(AccountingPeriodRepository periodRepository, PeriodLookup periodLookup,
                       JournalRepository journalRepository) {
        this.periodRepository = periodRepository;
        this.periodLookup = periodLookup;
        this.journalRepository = journalRepository;
    }

    public AccountingPeriod period(UUID id) {
        return periodRepository.findById(new AccountingPeriodId(id))
                .orElseThrow(() -> new ResourceNotFoundException("accounting period not found: " + id));
    }

    /** 記帳先の期を日付で探す。見つからなければ 404。 */
    public AccountingPeriod periodContaining(LocalDate date) {
        return periodLookup.findContaining(date)
                .orElseThrow(() -> new ResourceNotFoundException("no accounting period contains " + date));
    }

    /** 期首日がもっとも新しい期。会社が初期化されていなければ 404。 */
    public AccountingPeriod latestPeriod() {
        return periodLookup.findLatest()
                .orElseThrow(() -> new ResourceNotFoundException("no accounting period exists"));
    }

    public PostedJournal journal(JournalId id) {
        return journalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("journal not found: " + id.value()));
    }

    public List<PostedJournal> postedJournalsOf(AccountingPeriod period) {
        return journalRepository.findByPeriod(period);
    }

    /** ドメインサービスに渡す {@code journalsSoFar}。 */
    public List<Journal> journalsOf(AccountingPeriod period) {
        return PostedJournal.journalsOf(journalRepository.findByPeriod(period));
    }

    public PostedJournal postAndSave(AccountingPeriod period, Journal journal) {
        PostedJournal posted = PostedJournal.post(period, journal);
        journalRepository.save(posted);
        return posted;
    }

    public List<PostedJournal> postAllAndSave(AccountingPeriod period, List<Journal> journals) {
        List<PostedJournal> posted = PostedJournal.postAll(period, journals);
        journalRepository.saveAll(posted);
        return posted;
    }
}
