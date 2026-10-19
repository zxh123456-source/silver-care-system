package com.shanzhu.beadhouse.entity.po;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/** A committed recharge receipt. The mapper intentionally exposes no update/delete operation. */
@Data
public class DepositRechargeLedger {
    private Long id;
    private String requestId;
    private Long elderId;
    private Long staffId;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private Date createdAt;
}
