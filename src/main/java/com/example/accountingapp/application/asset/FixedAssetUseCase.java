package com.example.accountingapp.application.asset;

import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.FixedAssetId;
import com.example.accounting.model.asset.FixedAssetRepository;
import com.example.accounting.model.asset.UsageBasedDepreciationPolicy;
import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.period.AccountingPeriod;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.InvalidCommandException;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.OperationNotAllowedException;
import com.example.accountingapp.application.ResourceNotFoundException;
import com.example.accountingapp.application.port.AuditContext;
import com.example.accountingapp.application.port.FixedAssetDisposalRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 有形固定資産(CLAUDE.md §6.8): 登録、売却・除却・廃棄、圧縮記帳、固定資産台帳の照会。
 *
 * <p>帳簿価額は勘定の残高から導出するため、同じ種類の資産が複数あるなら資産ごとに科目が必要
 * (ライブラリの既知の制限)。科目は登録時に利用者が選ぶ(§12 の未決事項8。自動作成はしない)。
 * 取得そのものの仕訳(備品/現金 など)は、通常の仕訳として別に記帳する。
 */
@Service
public class FixedAssetUseCase {

    public static final String NAME = "FixedAsset";

    private final FixedAssetRepository fixedAssetRepository;
    private final FixedAssetDisposalRegistry disposals;
    private final JournalBook book;
    private final AccountResolver accounts;

    public FixedAssetUseCase(FixedAssetRepository fixedAssetRepository, FixedAssetDisposalRegistry disposals,
                             JournalBook book, AccountResolver accounts) {
        this.fixedAssetRepository = fixedAssetRepository;
        this.disposals = disposals;
        this.book = book;
        this.accounts = accounts;
    }

    public record RegisterCommand(
            String name, String assetAccountCode, String accumulatedDepreciationAccountCode,
            String depreciationExpenseAccountCode, long acquisitionCost, LocalDate acquisitionDate,
            int usefulLifeYears, long residualValue, DepreciationSettings depreciation) {
    }

    @Transactional
    public FixedAsset register(RegisterCommand command) {
        FixedAsset asset = new FixedAsset(
                FixedAssetId.newId(), command.name(),
                accounts.resolve(command.assetAccountCode()),
                accounts.resolve(command.accumulatedDepreciationAccountCode()),
                accounts.resolve(command.depreciationExpenseAccountCode()),
                Money.ofYen(command.acquisitionCost()), command.acquisitionDate(), command.usefulLifeYears(),
                Money.ofYen(command.residualValue()), command.depreciation().toPolicy());
        fixedAssetRepository.save(asset);
        return asset;
    }

    /** 固定資産台帳の1行。累計償却額・帳簿価額は、指定した期(省略時は最新の期)の仕訳から導出する。 */
    public record FixedAssetView(FixedAsset asset, DepreciationSettings depreciation, Money accumulatedDepreciation,
                                 Money bookValue, Optional<LocalDate> disposedOn) {
    }

    @Transactional(readOnly = true)
    public List<FixedAssetView> list(Optional<UUID> periodId) {
        List<Journal> journals = book.journalsOf(periodId.map(book::period).orElseGet(book::latestPeriod));
        return fixedAssetRepository.findAll().stream().map(asset -> view(asset, journals)).toList();
    }

    @Transactional(readOnly = true)
    public FixedAssetView get(UUID id, Optional<UUID> periodId) {
        List<Journal> journals = book.journalsOf(periodId.map(book::period).orElseGet(book::latestPeriod));
        return view(find(id), journals);
    }

    private FixedAssetView view(FixedAsset asset, List<Journal> journals) {
        return new FixedAssetView(asset, DepreciationSettings.of(asset.depreciationPolicy()),
                asset.accumulatedDepreciation(journals), asset.bookValue(journals), disposals.disposedOn(asset.id()));
    }

    /**
     * 売却・除却・廃棄に共通の指定。{@code recordDepreciationUntilDisposal} が true なら、処分日までの当期の
     * 減価償却(月割)を先に記帳する。生産高比例法の資産は、そのとき当期の利用量 {@code usageThisPeriod} が必要。
     */
    public record DisposalOptions(LocalDate date, boolean recordDepreciationUntilDisposal, Long usageThisPeriod) {
    }

    @Transactional
    public List<JournalId> sell(UUID id, DisposalOptions options, String proceedsAccountCode, long proceeds) {
        return dispose(id, options, (soFar, asset) -> asset.disposalEntry(
                soFar, options.date(), accounts.resolve(proceedsAccountCode), Money.ofYen(proceeds)));
    }

    @Transactional
    public List<JournalId> retire(UUID id, DisposalOptions options, long estimatedDisposalValue) {
        return dispose(id, options, (soFar, asset) -> asset.retirementEntry(
                soFar, options.date(), Money.ofYen(estimatedDisposalValue)));
    }

    @Transactional
    public List<JournalId> abandon(UUID id, DisposalOptions options, long disposalCost, String paymentAccountCode) {
        return dispose(id, options, (soFar, asset) -> asset.abandonmentEntry(
                soFar, options.date(), Money.ofYen(disposalCost), accounts.resolveOptional(paymentAccountCode)));
    }

    private interface DisposalEntry {
        Journal create(List<Journal> journalsSoFar, FixedAsset asset);
    }

    private List<JournalId> dispose(UUID id, DisposalOptions options, DisposalEntry entry) {
        return AuditContext.within(NAME, () -> {
            FixedAsset asset = find(id);
            if (disposals.disposedOn(asset.id()).isPresent()) {
                throw new OperationNotAllowedException("fixed asset is already disposed: " + id);
            }
            AccountingPeriod period = book.periodContaining(options.date());
            List<Journal> soFar = new ArrayList<>(book.journalsOf(period));
            List<Journal> generated = new ArrayList<>();
            if (options.recordDepreciationUntilDisposal()) {
                depreciationUntil(asset, soFar, period, options).ifPresent(journal -> {
                    soFar.add(journal);
                    generated.add(journal);
                });
            }
            generated.add(entry.create(soFar, asset));
            book.postAllAndSave(period, generated);
            disposals.markDisposed(asset.id(), options.date());
            return generated.stream().map(Journal::id).toList();
        });
    }

    private static Optional<Journal> depreciationUntil(FixedAsset asset, List<Journal> soFar, AccountingPeriod period,
                                                       DisposalOptions options) {
        if (asset.depreciationPolicy() instanceof UsageBasedDepreciationPolicy) {
            if (options.usageThisPeriod() == null) {
                throw new InvalidCommandException("usageThisPeriod is required for a usage-based asset: " + asset.name());
            }
            return asset.depreciationEntryByUsage(soFar, options.usageThisPeriod(), options.date());
        }
        return asset.depreciationEntry(soFar, period, options.date());
    }

    /**
     * 圧縮記帳(直接減額方式)。返された新しい {@link FixedAsset} と仕訳を同じトランザクションで保存する
     * (古いインスタンスを使い続けると取得価額が合わない)。
     */
    @Transactional
    public JournalId compress(UUID id, long reduction, LocalDate date) {
        return AuditContext.within(NAME, () -> {
            FixedAsset.Compression compression = find(id).compress(Money.ofYen(reduction), date);
            book.postAndSave(book.periodContaining(date), compression.entry());
            fixedAssetRepository.save(compression.compressedAsset());
            return compression.entry().id();
        });
    }

    private FixedAsset find(UUID id) {
        return fixedAssetRepository.findById(new FixedAssetId(id))
                .orElseThrow(() -> new ResourceNotFoundException("fixed asset not found: " + id));
    }
}
