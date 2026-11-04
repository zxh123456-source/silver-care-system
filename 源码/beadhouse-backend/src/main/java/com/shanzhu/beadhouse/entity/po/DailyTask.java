package com.shanzhu.beadhouse.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("ai_daily_task")
public class DailyTask {
    @TableId(type=IdType.AUTO) private Long id;
    private String taskKey;
    private Date taskDate;
    private Long elderId;
    private Long sourceId;
    private String category;
    private String title;
    private String detail;
    private String targetPath;
    private Date dueAt;
    private String state;
    private Long ownerId;
    private Integer revision;
    private String completionNote;
    private Date completedAt;
    private Date createTime;
    private Date updateTime;
    @TableField(exist=false) private String elderName;
    @TableField(exist=false) private String ownerName;
    public boolean isOverdue() { return !"DONE".equals(state) && dueAt != null && dueAt.before(new Date()); }
}
