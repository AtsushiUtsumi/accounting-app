package com.example.accountingapp.application.port;

import com.example.accounting.model.consolidation.CompanyId;

import java.util.List;
import java.util.Optional;

/** 会社の保存先。会社はアプリ側の概念(ライブラリは連結で会社を区別する {@link CompanyId} だけを持つ)。 */
public interface CompanyRepository {

    /** リクエストで会社を指定しなかったときに使う会社。既存のデータはこの会社のもの。 */
    CompanyId DEFAULT_COMPANY = CompanyId.of("00000000-0000-0000-0000-000000000001");

    Optional<Company> findById(CompanyId id);

    /** すべての会社(作った順)。 */
    List<Company> findAll();

    void save(Company company);

    record Company(CompanyId id, String name) {
    }
}
