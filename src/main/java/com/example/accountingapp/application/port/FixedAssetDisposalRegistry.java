package com.example.accountingapp.application.port;

import com.example.accounting.model.asset.FixedAssetId;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 固定資産を売却・除却・廃棄した記録。ライブラリの {@code FixedAsset} は状態を持たないため、
 * 手放した資産を決算整理の減価償却の対象から外すのに、アプリ側で記録しておく。
 */
public interface FixedAssetDisposalRegistry {

    void markDisposed(FixedAssetId id, LocalDate date);

    Optional<LocalDate> disposedOn(FixedAssetId id);
}
