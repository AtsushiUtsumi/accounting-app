package com.example.accountingapp.presentation;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/** 入力の日付が省略されたときの初期値。「今日」が必要なのはここだけで、ドメインには {@link LocalDate} で渡す。 */
@Component
public class Today {

    private final Clock clock;

    public Today(Clock clock) {
        this.clock = clock;
    }

    public LocalDate orToday(LocalDate date) {
        return date != null ? date : LocalDate.now(clock);
    }
}
