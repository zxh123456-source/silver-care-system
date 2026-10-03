package com.shanzhu.beadhouse.entity.po;

import com.shanzhu.beadhouse.entity.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Date;

/**
 * <p>
 * 老人健康数据表
 * </p>
 *
 * @author: ShanZhu
 * @date: 2024-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HealthData extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 老人编号
     */
    private Long elderId;

    /**
     * 实际测量时间
     */
    private Date measureTime;

    /**
     * 记录备注
     */
    private String remarks;

    /**
     * 身高
     */
    private Integer height;

    /**
     * 体重
     */
    private Double weight;

    /**
     * 体温
     */
    private Double temperature;

    /**
     * 心率
     */
    private Integer heartRate;

    /**
     * 收缩血压
     */
    private Integer systolicBloodPressure;

    /**
     * 舒张血压
     */
    private Integer diastolicBloodPressure;

    /**
     * 空腹血糖
     */
    private Double fastingBloodGlucose;

    /**
     * 餐后血糖
     */
    private Double postprandialBloodGlucose;

    /**
     * 血氧饱和度
     */
    private Integer bloodOxygenSaturation;

    /**
     * 总胆固醇
     */
    private Integer cholesterol;

    /**
     * 尿酸
     */
    private Integer uricAcid;

    /**
     * 左眼
     */
    private Double leftEye;

    /**
     * 右眼
     */
    private Double rightEye;

    /**
     * 左耳
     */
    private String leftEar;

    /**
     * 右耳
     */
    private String rightEar;

    /**
     * 肌肉率
     */
    private Integer musclePercentage;

    /**
     * 体脂率
     */
    private Integer bodyFatPercentage;

    /**
     * 腰围
     */
    private Integer waistCircumference;

    /**
     * 臀围
     */
    private Integer hipCircumference;

    /**
     * 水分率
     */
    private Integer moistureContent;


}
