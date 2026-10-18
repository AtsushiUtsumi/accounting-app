package com.example.accountingapp.application.asset;

import com.example.accounting.model.asset.FixedAssetId;
import com.example.accounting.model.asset.IntangibleAsset;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.money.Money;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.port.IntangibleAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 無形固定資産(ソフトウェア・のれんなど)の登録と照会。償却は決算整理で行う。
 * 取得そのものの仕訳は、通常の仕訳として別に記帳する(帳簿価額は資産の勘定の残高から導出するため)。
 */
@Service
public class IntangibleAssetUseCase {

    private final IntangibleAssetRepository repository;
    private final JournalBook book;
    private final AccountResolver accounts;

    public IntangibleAssetUseCase(IntangibleAssetRepository repository, JournalBook book, AccountResolver accounts) {
        this.repository = repository;
        this.book = book;
        this.accounts = accounts;
    }

    public record RegisterCommand(String name, String assetAccountCode, String amortizationExpenseAccountCode,
                                  long acquisitionCost, LocalDate acquisitionDate, int usefulLifeYears) {
    }

    @Transactional
    public IntangibleAsset register(RegisterCommand command) {
        IntangibleAsset asset = new IntangibleAsset(
                FixedAssetId.newId(), command.name(),
                accounts.resolve(command.assetAccountCode()),
                accounts.resolve(command.amortizationExpenseAccountCode()),
                Money.ofYen(command.acquisitionCost()), command.acquisitionDate(), command.usefulLifeYears());
        repository.save(asset);
        return asset;
    }

    public record IntangibleAssetView(IntangibleAsset asset, Money bookValue) {
    }

    @Transactional(readOnly = true)
    public List<IntangibleAssetView> list(Optional<UUID> periodId) {
        List<Journal> journals = book.journalsOf(periodId.map(book::period).orElseGet(book::latestPeriod));
        return repository.findAll().stream()
                .map(asset -> new IntangibleAssetView(asset, asset.bookValue(journals)))
                .toList();
    }
}
