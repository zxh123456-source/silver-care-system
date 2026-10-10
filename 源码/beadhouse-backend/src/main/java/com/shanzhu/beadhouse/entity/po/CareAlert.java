package com.shanzhu.beadhouse.entity.po;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;
@Data
@TableName("ai_care_alert")
public class CareAlert {
    @TableId(type=IdType.AUTO) private Long id;
    private String alertKey;
    private Long elderId;
    private Long sourceId;
    private String kind;
    private String detail;
    private String evidenceKey;
    private String taskKey;
    private String state;
    private Long reviewerId;
    private Integer revision;
    private String resolutionNote;
    private Date resolvedAt;
    private Date createTime;
    private Date updateTime;
    @TableField(exist=false) private String elderName;
}
