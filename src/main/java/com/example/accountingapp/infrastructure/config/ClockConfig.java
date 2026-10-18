package com.example.accountingapp.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** 「今日」は Clock から取る。テストでは Clock.fixed に差し替える。 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Tokyo"));
    }
}
