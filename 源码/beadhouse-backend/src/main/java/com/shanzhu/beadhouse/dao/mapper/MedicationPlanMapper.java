package com.shanzhu.beadhouse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.MedicationPlan;

public interface MedicationPlanMapper extends BaseMapper<MedicationPlan> {
    MedicationPlan lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
