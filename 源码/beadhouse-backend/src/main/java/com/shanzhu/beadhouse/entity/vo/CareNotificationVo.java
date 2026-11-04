package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

@Data
public class CareNotificationVo {
    private Long id;
    private Long elderId;
    private String elderName;
    private String kind;
    private String detail;
    private String state;
    private Long noticeRevision;
}
