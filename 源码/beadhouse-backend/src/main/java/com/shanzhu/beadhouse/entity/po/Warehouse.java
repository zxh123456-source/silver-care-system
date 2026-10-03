package com.shanzhu.beadhouse.entity.po;

import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 仓库表
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Warehouse extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 仓库管理员编号
     */
    private Long staffId;

    /**
     * 仓库名称
     */
    private String name;

    /**
     * 删除状态（Y/N）
     */
    private String delFlag;


}
