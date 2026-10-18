package com.example.accountingapp.application.port;

import com.example.accounting.model.period.AccountingPeriod;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 会計期間の検索。ライブラリの {@code AccountingPeriodRepository} にない検索をアプリ側で足す。 */
public interface PeriodLookup {

    /** その日付を含む期(OPEN・CLOSED を問わない)。 */
    Optional<AccountingPeriod> findContaining(LocalDate date);

    /** 直前の期(期末日が、この期の期首日の前日である期)。 */
    Optional<AccountingPeriod> findPrevious(AccountingPeriod period);

    /** 期首日がもっとも新しい期。 */
    Optional<AccountingPeriod> findLatest();

    /** すべての期(期首日の新しい順)。 */
    List<AccountingPeriod> findAll();
}
