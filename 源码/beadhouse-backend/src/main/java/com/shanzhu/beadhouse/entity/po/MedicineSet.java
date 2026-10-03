package com.shanzhu.beadhouse.entity.po;

import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 用药设置表
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MedicineSet extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 药品缴存信息编号
     */
    private Long depositInfoId;

    /**
     * 用药时间（餐前/餐后）
     */
    private String medicineTime;

    /**
     * 天频率
     */
    private Integer dayFrequency;


}
