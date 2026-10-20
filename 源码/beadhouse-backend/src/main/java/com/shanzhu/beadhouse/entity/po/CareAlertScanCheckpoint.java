package com.shanzhu.beadhouse.entity.po;

import lombok.Data;
import java.util.Date;

@Data
public class CareAlertScanCheckpoint {
    private Long id;
    private java.sql.Date scanDate;
    private Long planCursor;
    private java.sql.Date targetDate;
    private java.sql.Date lastCompletedDate;
    private Date lastSuccessAt;
    private String lastError;
    private String leaseOwner;
    private Long leaseToken;
    private Date leaseUntil;
    private Date nextScanAt;
}
