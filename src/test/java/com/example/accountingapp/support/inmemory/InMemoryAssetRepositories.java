package com.example.accountingapp.support.inmemory;

import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.FixedAssetId;
import com.example.accounting.model.asset.FixedAssetRepository;
import com.example.accounting.model.asset.IntangibleAsset;
import com.example.accounting.model.inventory.InventoryMovement;
import com.example.accountingapp.application.port.FixedAssetDisposalRegistry;
import com.example.accountingapp.application.port.HeldToMaturityBondRepository;
import com.example.accountingapp.application.port.IntangibleAssetRepository;
import com.example.accountingapp.application.port.InventoryMovementRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 資産・商品有高帳まわりのインメモリ実装。 */
public final class InMemoryAssetRepositories {

    private InMemoryAssetRepositories() {
    }

    public static final class FixedAssets implements FixedAssetRepository, FixedAssetDisposalRegistry {

        private final Map<FixedAssetId, FixedAsset> assets = new LinkedHashMap<>();
        private final Map<FixedAssetId, LocalDate> disposals = new HashMap<>();

        @Override
        public Optional<FixedAsset> findById(FixedAssetId id) {
            return Optional.ofNullable(assets.get(id));
        }

        @Override
        public List<FixedAsset> findAll() {
            return new ArrayList<>(assets.values());
        }

        @Override
        public void save(FixedAsset asset) {
            assets.put(asset.id(), asset);
        }

        @Override
        public void markDisposed(FixedAssetId id, LocalDate date) {
            disposals.put(id, date);
        }

        @Override
        public Optional<LocalDate> disposedOn(FixedAssetId id) {
            return Optional.ofNullable(disposals.get(id));
        }
    }

    public static final class IntangibleAssets implements IntangibleAssetRepository {

        private final Map<FixedAssetId, IntangibleAsset> assets = new LinkedHashMap<>();

        @Override
        public Optional<IntangibleAsset> findById(FixedAssetId id) {
            return Optional.ofNullable(assets.get(id));
        }

        @Override
        public List<IntangibleAsset> findAll() {
            return new ArrayList<>(assets.values());
        }

        @Override
        public void save(IntangibleAsset asset) {
            assets.put(asset.id(), asset);
        }
    }

    public static final class Bonds implements HeldToMaturityBondRepository {

        private final Map<UUID, RegisteredBond> bonds = new LinkedHashMap<>();

        @Override
        public Optional<RegisteredBond> findById(UUID id) {
            return Optional.ofNullable(bonds.get(id));
        }

        @Override
        public List<RegisteredBond> findAll() {
            return new ArrayList<>(bonds.values());
        }

        @Override
        public void save(RegisteredBond bond) {
            bonds.put(bond.id(), bond);
        }
    }

    public static final class InventoryMovements implements InventoryMovementRepository {

        private final Map<String, List<InventoryMovement>> movements = new HashMap<>();

        @Override
        public void append(String itemCode, InventoryMovement movement) {
            movements.computeIfAbsent(itemCode, code -> new ArrayList<>()).add(movement);
        }

        @Override
        public List<InventoryMovement> findByItem(String itemCode) {
            return List.copyOf(movements.getOrDefault(itemCode, List.of()));
        }
    }
}
