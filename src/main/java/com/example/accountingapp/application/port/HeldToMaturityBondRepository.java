package com.example.accountingapp.application.port;

import com.example.accounting.model.securities.HeldToMaturityBond;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 満期保有目的債券の保存先。ライブラリの {@link HeldToMaturityBond} は ID を持たないため、
 * アプリ側の ID と組({@link RegisteredBond})で保存する。帳簿価額は保存しない。
 */
public interface HeldToMaturityBondRepository {

    Optional<RegisteredBond> findById(UUID id);

    List<RegisteredBond> findAll();

    void save(RegisteredBond bond);

    /** アプリ側の ID と、ライブラリの債券の組。 */
    record RegisteredBond(UUID id, String name, HeldToMaturityBond bond) {
    }
}
