package com.example.accountingapp.support;

import com.example.accounting.model.account.StandardAccounts;

import java.time.LocalDate;

import static com.example.accountingapp.support.TestAccounting.cr;
import static com.example.accountingapp.support.TestAccounting.dr;

/**
 * 「サンプル商店」4月の取引(ドメインの TrialBalanceTest・ClosingServiceTest と同じ例題)。
 * <pre>
 * 4/1  現金600,000円を出資して開業した。      (借)現金600,000      / (貸)資本金600,000
 * 4/5  銀行から現金200,000円を借り入れた。    (借)現金200,000      / (貸)借入金200,000
 * 4/10 商品300,000円を掛けで仕入れた。        (借)仕入300,000      / (貸)買掛金300,000
 * 4/15 商品を280,000円で掛け売上した。        (借)売掛金280,000    / (貸)売上280,000
 * 4/20 買掛金150,000円を現金で支払った。      (借)買掛金150,000    / (貸)現金150,000
 * 4/25 売掛金180,000円を現金で回収した。      (借)現金180,000      / (貸)売掛金180,000
 * </pre>
 * 合計試算表の借方・貸方合計は1,710,000円。締めると当期純損失20,000円、
 * 締め後の B/S は資産930,000円・負債350,000円・純資産580,000円。
 */
public final class SampleShop {

    public static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);

    private SampleShop() {
    }

    public static void postAprilJournals(TestAccounting app) {
        app.post(LocalDate.of(2026, 4, 1), "出資による開業",
                dr(StandardAccounts.CASH, 600_000), cr(StandardAccounts.CAPITAL_STOCK, 600_000));
        app.post(LocalDate.of(2026, 4, 5), "銀行からの借入",
                dr(StandardAccounts.CASH, 200_000), cr(StandardAccounts.LOANS_PAYABLE, 200_000));
        app.post(LocalDate.of(2026, 4, 10), "商品の掛け仕入",
                dr(StandardAccounts.PURCHASES, 300_000), cr(StandardAccounts.ACCOUNTS_PAYABLE, 300_000));
        app.post(LocalDate.of(2026, 4, 15), "商品の掛け売上",
                dr(StandardAccounts.ACCOUNTS_RECEIVABLE, 280_000), cr(StandardAccounts.SALES, 280_000));
        app.post(LocalDate.of(2026, 4, 20), "買掛金の現金支払",
                dr(StandardAccounts.ACCOUNTS_PAYABLE, 150_000), cr(StandardAccounts.CASH, 150_000));
        app.post(LocalDate.of(2026, 4, 25), "売掛金の現金回収",
                dr(StandardAccounts.CASH, 180_000), cr(StandardAccounts.ACCOUNTS_RECEIVABLE, 180_000));
    }
}
