package com.shanzhu.beadhouse.entity.vo;

import lombok.Data;

@Data
public class PolicyCitationVo {
    private Long id;
    private String title;
    private String source;
    private Integer sectionNo;
    private String content;
    private Double score;
}
