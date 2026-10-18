package com.example.accountingapp.application.asset;

import com.example.accounting.model.asset.DecliningBalanceDepreciationPolicy;
import com.example.accounting.model.asset.DepreciationPolicy;
import com.example.accounting.model.asset.FixedAsset;
import com.example.accounting.model.asset.StraightLineDepreciationPolicy;
import com.example.accounting.model.asset.UnitsOfProductionDepreciationPolicy;
import com.example.accounting.model.asset.UsageBasedDepreciationPolicy;
import com.example.accounting.model.money.Money;
import com.example.accounting.model.money.RoundingPolicy;
import com.example.accountingapp.application.InvalidCommandException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 固定資産の登録時に受け取った償却方法の値。ライブラリの {@link DepreciationPolicy} の実装は償却率などを
 * 外に出さないため、アプリがこの値を保存し、復元時に {@link #toPolicy()} で作り直す(CLAUDE.md §5)。
 * {@code RoundingPolicy} もラムダなので、{@link Rounding} で保存する。
 *
 * @param decliningRate       定率法の償却率(定率法のときだけ)
 * @param totalEstimatedUsage 生産高比例法の総利用可能量(生産高比例法のときだけ)
 */
public record DepreciationSettings(Method method, BigDecimal decliningRate, Long totalEstimatedUsage, Rounding rounding) {

    public enum Method {
        STRAIGHT_LINE, DECLINING_BALANCE, UNITS_OF_PRODUCTION
    }

    public enum Rounding {
        DOWN, HALF_UP;

        RoundingPolicy toPolicy() {
            return this == DOWN ? RoundingPolicy.roundDown() : RoundingPolicy.roundHalfUp();
        }
    }

    public DepreciationSettings {
        if (method == null) {
            throw new InvalidCommandException("depreciation method is required");
        }
        if (rounding == null) {
            rounding = Rounding.DOWN;
        }
        if (method == Method.DECLINING_BALANCE && decliningRate == null) {
            throw new InvalidCommandException("decliningRate is required for the declining-balance method");
        }
        if (method == Method.UNITS_OF_PRODUCTION && totalEstimatedUsage == null) {
            throw new InvalidCommandException("totalEstimatedUsage is required for the units-of-production method");
        }
        if (method != Method.DECLINING_BALANCE) {
            decliningRate = null;
        } else {
            // DB の numeric(6,4) から読むと 0.2500 になるため、尺度をそろえて往復で等しくする
            decliningRate = decliningRate.stripTrailingZeros();
        }
        if (method != Method.UNITS_OF_PRODUCTION) {
            totalEstimatedUsage = null;
        }
    }

    /** ライブラリの償却方法を作り、この値を取り出せるように包んで返す。 */
    public DepreciationPolicy toPolicy() {
        return switch (method) {
            case STRAIGHT_LINE -> new Recorded(this, StraightLineDepreciationPolicy.of(rounding.toPolicy()));
            case DECLINING_BALANCE ->
                    new Recorded(this, DecliningBalanceDepreciationPolicy.of(decliningRate, rounding.toPolicy()));
            case UNITS_OF_PRODUCTION -> new RecordedUsageBased(
                    this, UnitsOfProductionDepreciationPolicy.of(totalEstimatedUsage, rounding.toPolicy()));
        };
    }

    /** 固定資産の償却方法から、登録時の値を取り出す。{@link #toPolicy()} で作った償却方法でなければ例外。 */
    public static DepreciationSettings of(DepreciationPolicy policy) {
        if (policy instanceof HasSettings recorded) {
            return recorded.settings();
        }
        throw new IllegalStateException("depreciation policy was not created from DepreciationSettings: " + policy);
    }

    private sealed interface HasSettings permits Recorded, RecordedUsageBased {
        DepreciationSettings settings();
    }

    /**
     * 期間で償却する方法(定額法・定率法)。計算はすべてライブラリに委ねる。
     * 等価性は登録時の値で判定する(保存 → 復元の往復で等しくなるように)。
     */
    private static final class Recorded implements DepreciationPolicy, HasSettings {

        private final DepreciationSettings settings;
        private final DepreciationPolicy delegate;

        private Recorded(DepreciationSettings settings, DepreciationPolicy delegate) {
            this.settings = settings;
            this.delegate = delegate;
        }

        @Override
        public DepreciationSettings settings() {
            return settings;
        }

        @Override
        public Money depreciationFor(FixedAsset asset, Money accumulatedDepreciationBeforeThisPeriod) {
            return delegate.depreciationFor(asset, accumulatedDepreciationBeforeThisPeriod);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Recorded other && settings.equals(other.settings);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Recorded.class, settings);
        }

        @Override
        public String toString() {
            return "DepreciationPolicy" + settings;
        }
    }

    /** 利用量で償却する方法(生産高比例法)。ライブラリが利用量ベースと判定できるよう、同じインターフェースを実装する。 */
    private static final class RecordedUsageBased implements UsageBasedDepreciationPolicy, HasSettings {

        private final DepreciationSettings settings;
        private final UsageBasedDepreciationPolicy delegate;

        private RecordedUsageBased(DepreciationSettings settings, UsageBasedDepreciationPolicy delegate) {
            this.settings = settings;
            this.delegate = delegate;
        }

        @Override
        public DepreciationSettings settings() {
            return settings;
        }

        @Override
        public Money depreciationFor(FixedAsset asset, Money accumulatedDepreciationBeforeThisPeriod) {
            return delegate.depreciationFor(asset, accumulatedDepreciationBeforeThisPeriod);
        }

        @Override
        public Money depreciationForUsage(FixedAsset asset, long usageThisPeriod) {
            return delegate.depreciationForUsage(asset, usageThisPeriod);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RecordedUsageBased other && settings.equals(other.settings);
        }

        @Override
        public int hashCode() {
            return Objects.hash(RecordedUsageBased.class, settings);
        }

        @Override
        public String toString() {
            return "DepreciationPolicy" + settings;
        }
    }
}
