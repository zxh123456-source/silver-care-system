package com.shanzhu.beadhouse.entity.po;

import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 缴存药品信息表
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DepositInfo extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 药品缴存编号
     */
    private Long depositId;

    /**
     * 药品编号
     */
    private Long medicineId;

    /**
     * 缴存数量
     */
    private Integer depositNum;

    /**
     * 剩余数量
     */
    private Integer surplusNum;

    /**
     * 预警数量
     */
    private Integer warnNum;


}
