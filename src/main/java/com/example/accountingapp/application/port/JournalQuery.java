package com.example.accountingapp.application.port;

import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.journal.JournalId;
import com.example.accounting.model.journal.JournalType;
import com.example.accounting.model.period.AccountingPeriod;

import java.util.Set;

/** 仕訳についての、アプリ都合の問い合わせ(二重実行・二重取消の防止、使用済み科目の判定)。 */
public interface JournalQuery {

    boolean existsByPeriodAndType(AccountingPeriod period, JournalType type);

    /**
     * その期に、指定したユースケースが作った指定の種類の仕訳のうち、まだ取り消されていないもの
     * (赤黒の赤が記帳されていないもの)が1件でもあるか。決算整理の二重実行の防止に使う。
     */
    boolean existsUnreversedByPeriodAndTypeAndOrigin(AccountingPeriod period, JournalType type, String origin);

    /** その仕訳を取り消す仕訳({@code reversalOf} がその仕訳を指すもの)が記帳済みか。 */
    boolean isReversed(JournalId id);

    /** その期に記帳された仕訳のうち、取り消されたもの(取消仕訳はどの期にあってもよい)の ID。仕訳帳の表示用。 */
    Set<JournalId> findReversedIds(AccountingPeriod period);

    /** その科目を使った仕訳が1件でもあるか。 */
    boolean isAccountUsed(AccountCode code);
}
