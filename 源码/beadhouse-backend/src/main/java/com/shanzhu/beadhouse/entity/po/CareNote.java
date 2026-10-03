package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** AI 护理记录：AI 只生成草稿，确认后才写入。 */
@Data
@TableName("care_note")
@EqualsAndHashCode(callSuper = true)
public class CareNote extends BaseEntity {
    private static final long serialVersionUID = 1L;
    private Long elderId;
    @TableField(exist = false)
    private String elderName;
    private Long staffId;
    @TableField(exist = false)
    private String staffName;
    private Date eventTime;
    private String sourceText;
    private String observation;
    private String actionTaken;
    private String followUp;
    private String appetite;
    private String sleep;
    private String medicine;
    private String activity;
    private String status;
    private String aiGenerated;
}
