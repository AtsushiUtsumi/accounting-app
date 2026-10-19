package com.example.accountingapp.infrastructure.config;

import com.example.accounting.model.statement.CashFlowClassifier;
import com.example.accounting.model.statement.StandardCashFlowClassifier;
import com.example.accounting.service.AdjustingEntryService;
import com.example.accounting.service.BadDebtService;
import com.example.accounting.service.BondService;
import com.example.accounting.service.CashOverShortService;
import com.example.accounting.service.ClosingService;
import com.example.accounting.service.ConsolidationService;
import com.example.accounting.service.DividendService;
import com.example.accounting.service.FinancialStatementFactory;
import com.example.accounting.service.InstallmentPurchaseService;
import com.example.accounting.service.MergerService;
import com.example.accounting.service.NotesReceivableService;
import com.example.accounting.service.PerpetualInventoryService;
import com.example.accounting.service.ProvisionService;
import com.example.accounting.service.ReversalService;
import com.example.accounting.service.RevenueRecognitionService;
import com.example.accounting.service.TradingSecuritiesService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ドメインサービスの Bean 登録。ライブラリのクラスにはアノテーションを付けられないため、ここで登録する。
 * どれも引数なしのコンストラクタで作れ、副作用もないので、すべてシングルトンでよい。
 */
@Configuration(proxyBeanMethods = false)
public class DomainServiceConfig {

    @Bean
    ClosingService closingService() {
        return new ClosingService();
    }

    @Bean
    AdjustingEntryService adjustingEntryService() {
        return new AdjustingEntryService();
    }

    @Bean
    ReversalService reversalService() {
        return new ReversalService();
    }

    @Bean
    FinancialStatementFactory financialStatementFactory() {
        return new FinancialStatementFactory();
    }

    /** 会社ごとに差し替え可。 */
    @Bean
    CashFlowClassifier cashFlowClassifier() {
        return new StandardCashFlowClassifier();
    }

    // ---- 個別取引 ----

    @Bean
    BadDebtService badDebtService() {
        return new BadDebtService();
    }

    @Bean
    BondService bondService() {
        return new BondService();
    }

    @Bean
    CashOverShortService cashOverShortService() {
        return new CashOverShortService();
    }

    @Bean
    DividendService dividendService() {
        return new DividendService();
    }

    @Bean
    InstallmentPurchaseService installmentPurchaseService() {
        return new InstallmentPurchaseService();
    }

    @Bean
    MergerService mergerService() {
        return new MergerService();
    }

    @Bean
    NotesReceivableService notesReceivableService() {
        return new NotesReceivableService();
    }

    @Bean
    PerpetualInventoryService perpetualInventoryService() {
        return new PerpetualInventoryService();
    }

    @Bean
    ProvisionService provisionService() {
        return new ProvisionService();
    }

    @Bean
    RevenueRecognitionService revenueRecognitionService() {
        return new RevenueRecognitionService();
    }

    @Bean
    TradingSecuritiesService tradingSecuritiesService() {
        return new TradingSecuritiesService();
    }

    @Bean
    ConsolidationService consolidationService() {
        return new ConsolidationService();
    }
}
