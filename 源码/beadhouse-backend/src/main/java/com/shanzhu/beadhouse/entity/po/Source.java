package com.shanzhu.beadhouse.entity.po;

import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 来源渠道表
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Source extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 来源渠道名称
     */
    private String name;

    /**
     * 删除状态（Y/N）
     */
    private String delFlag;


}
