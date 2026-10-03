package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("policy_doc")
@EqualsAndHashCode(callSuper = true)
public class PolicyDoc extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private String title;
    private String source;
    private Integer sectionNo;
    private String content;
    private String keywords;
}
