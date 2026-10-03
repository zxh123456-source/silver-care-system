package com.shanzhu.beadhouse.entity.po;

import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 护工小组成员表
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NurseGroupMember extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 护工小组编号
     */
    private Long groupId;

    /**
     * 护工编号
     */
    private Long staffId;


}
