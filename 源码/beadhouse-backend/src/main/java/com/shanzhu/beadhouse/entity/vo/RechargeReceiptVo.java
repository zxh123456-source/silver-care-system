package com.shanzhu.beadhouse.entity.vo;

import com.shanzhu.beadhouse.entity.po.DepositRechargeLedger;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@AllArgsConstructor
public class RechargeReceiptVo {
    private Long id;
    private String requestId;
    private Long elderId;
    private Long staffId;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private Date createdAt;

    public static RechargeReceiptVo from(DepositRechargeLedger ledger) {
        return new RechargeReceiptVo(ledger.getId(), ledger.getRequestId(), ledger.getElderId(),
                ledger.getStaffId(), ledger.getAmount(), ledger.getBalanceBefore(),
                ledger.getBalanceAfter(), ledger.getCreatedAt());
    }
}
