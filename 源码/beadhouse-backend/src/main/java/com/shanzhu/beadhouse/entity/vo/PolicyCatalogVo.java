package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

@Data
public class PolicyCatalogVo {
    private String title;
    private String source;
    private Integer sectionCount;
    private String contentHash;
    private Integer duplicateCount;
    private String ragStatus;
    private Integer ragAttemptCount;
    private String ragLastErrorCode;
}
