package com.shanzhu.beadhouse.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PolicyOcrStatusVo {
    private boolean enabled;
    private boolean available;
    private String language;
    private String message;
}
