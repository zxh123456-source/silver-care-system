package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import java.math.BigDecimal;

/** Exact amounts supported by the existing DECIMAL(10,2) balance and ledger columns. */
public final class BillingAmount {
    public static final BigDecimal MAXIMUM = new BigDecimal("99999999.99");
    private BillingAmount() {}

    public static BigDecimal require(BigDecimal value, boolean allowZero) {
        if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0) || value.compareTo(MAXIMUM) > 0) {
            throw new BusinessRuntimeException(400, allowZero ? "金额须为0至99999999.99元" : "金额须大于0且不超过99999999.99元");
        }
        BigDecimal exact = value.stripTrailingZeros();
        if (exact.scale() > 2) throw new BusinessRuntimeException(400, "金额最多保留两位小数");
        return exact.setScale(2);
    }

    public static int quantity(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                || value.stripTrailingZeros().scale() > 0) {
            throw new BusinessRuntimeException(400, "服务次数或菜品份数须为正整数且不能超出整数范围");
        }
        return value.intValueExact();
    }
}
