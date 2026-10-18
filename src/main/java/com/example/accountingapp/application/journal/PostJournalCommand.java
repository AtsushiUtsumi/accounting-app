package com.example.accountingapp.application.journal;

import com.example.accounting.model.journal.JournalType;
import com.example.accountingapp.application.JournalLineCommand;

import java.time.LocalDate;
import java.util.List;

/** 仕訳の記帳の入力。種類は NORMAL または ADJUSTING。 */
public record PostJournalCommand(LocalDate date, String description, JournalType type, List<JournalLineCommand> lines) {
}
