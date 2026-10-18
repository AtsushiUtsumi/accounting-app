package com.example.accountingapp.application.asset;

import com.example.accounting.model.journal.Journal;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.securities.HeldToMaturityBond;
import com.example.accountingapp.application.AccountResolver;
import com.example.accountingapp.application.JournalBook;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository.RegisteredBond;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 満期保有目的債券の登録と照会。償却原価法は決算整理で行う。
 * 取得そのものの仕訳は、通常の仕訳(または端数利息付きの債券購入)として別に記帳する。
 */
@Service
public class HeldToMaturityBondUseCase {

    private final HeldToMaturityBondRepository repository;
    private final JournalBook book;
    private final AccountResolver accounts;

    public HeldToMaturityBondUseCase(HeldToMaturityBondRepository repository, JournalBook book,
                                     AccountResolver accounts) {
        this.repository = repository;
        this.book = book;
        this.accounts = accounts;
    }

    public record RegisterCommand(String name, String bondAccountCode, long faceValue, long acquisitionCost,
                                  LocalDate acquisitionDate, LocalDate maturityDate) {
    }

    @Transactional
    public RegisteredBond register(RegisterCommand command) {
        HeldToMaturityBond bond = new HeldToMaturityBond(
                accounts.resolve(command.bondAccountCode()), Money.ofYen(command.faceValue()),
                Money.ofYen(command.acquisitionCost()), command.acquisitionDate(), command.maturityDate());
        RegisteredBond registered = new RegisteredBond(UUID.randomUUID(), command.name(), bond);
        repository.save(registered);
        return registered;
    }

    public record BondView(RegisteredBond bond, Money bookValue) {
    }

    @Transactional(readOnly = true)
    public List<BondView> list(Optional<UUID> periodId) {
        List<Journal> journals = book.journalsOf(periodId.map(book::period).orElseGet(book::latestPeriod));
        return repository.findAll().stream()
                .map(registered -> new BondView(registered, registered.bond().bookValue(journals)))
                .toList();
    }
}
