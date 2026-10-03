package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

@Data
public class ElderStaffAssignmentVo {
    private Long id;
    private Long elderId;
    private String elderName;
    private Long staffId;
    private String staffName;
    private String active;
}
