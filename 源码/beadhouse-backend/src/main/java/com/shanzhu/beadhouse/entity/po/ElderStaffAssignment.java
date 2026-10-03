package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("elder_staff_assignment")
@EqualsAndHashCode(callSuper = true)
public class ElderStaffAssignment extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long elderId;
    private Long staffId;
    private String active;
}
