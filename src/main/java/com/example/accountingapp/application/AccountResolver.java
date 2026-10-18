package com.example.accountingapp.application;

import com.example.accounting.model.account.Account;
import com.example.accounting.model.account.AccountCode;
import com.example.accounting.model.account.AccountRepository;
import com.example.accounting.model.journal.JournalLine;
import com.example.accounting.model.money.Money;
import org.springframework.stereotype.Component;

import java.util.List;

/** 入力の科目コードを {@link Account} に変換する。無効化された科目の検査は {@link JournalLine} が行う。 */
@Component
public class AccountResolver {

    private final AccountRepository accountRepository;

    public AccountResolver(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account resolve(String code) {
        return accountRepository.findByCode(AccountCode.of(code))
                .orElseThrow(() -> new ResourceNotFoundException("account not found: " + code));
    }

    /** 省略可能な科目。コードが null なら null を返す。 */
    public Account resolveOptional(String code) {
        return code == null ? null : resolve(code);
    }

    public JournalLine toLine(JournalLineCommand command) {
        return new JournalLine(resolve(command.accountCode()), command.side(), Money.ofYen(command.amount()));
    }

    public List<JournalLine> toLines(List<JournalLineCommand> commands) {
        return commands.stream().map(this::toLine).toList();
    }
}
