package com.example.accountingapp.application.port;

import com.example.accounting.model.asset.FixedAssetId;
import com.example.accounting.model.asset.IntangibleAsset;

import java.util.List;
import java.util.Optional;

/** 無形固定資産の保存先(ライブラリにリポジトリがないため、アプリ側で定義する)。帳簿価額は保存しない。 */
public interface IntangibleAssetRepository {

    Optional<IntangibleAsset> findById(FixedAssetId id);

    List<IntangibleAsset> findAll();

    void save(IntangibleAsset asset);
}
