package com.example.accountingapp.application.port;

import com.example.accounting.model.consolidation.CompanyId;
import com.example.accounting.model.consolidation.Subsidiary;

import java.util.List;
import java.util.Optional;

/**
 * 子会社(支配獲得時の事実)の保存先。ライブラリの {@link Subsidiary} は親会社を持たないため、親会社と組で保存する。
 * 支配獲得日の資本の科目は、いま操作している会社(親会社)の科目表で組み立てる。
 */
public interface SubsidiaryRepository {

    /** その会社が親会社である子会社(支配獲得日の順)。 */
    List<Subsidiary> findByParent(CompanyId parent);

    /** その会社の親会社(子会社でなければ空)。 */
    Optional<CompanyId> findParentOf(CompanyId subsidiary);

    void save(CompanyId parent, Subsidiary subsidiary);

    void delete(CompanyId subsidiary);
}
