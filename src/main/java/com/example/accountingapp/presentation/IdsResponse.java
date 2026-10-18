package com.example.accountingapp.presentation;

import com.example.accounting.model.journal.JournalId;

import java.util.List;
import java.util.UUID;

/** 記帳した仕訳の ID の一覧。 */
public record IdsResponse(List<UUID> journalIds) {

    public static IdsResponse of(List<JournalId> ids) {
        return new IdsResponse(ids.stream().map(JournalId::value).toList());
    }
}
