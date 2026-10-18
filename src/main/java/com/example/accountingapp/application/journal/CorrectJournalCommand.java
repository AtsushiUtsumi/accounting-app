package com.example.accountingapp.application.journal;

import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accountingapp.application.JournalLineCommand;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 赤黒訂正の入力。{@code correctJournal} を省略すると取消(赤)だけを記帳する。
 * 正しい仕訳(黒)の日付は訂正日にする。
 */
public record CorrectJournalCommand(JournalId originalId, LocalDate date, Optional<CorrectedJournal> correctJournal) {

    /** 正しい仕訳(黒)の内容。 */
    public record CorrectedJournal(String description, JournalType type, List<JournalLineCommand> lines) {
    }
}
