package com.shanzhu.beadhouse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.MedicationExecution;
import org.apache.ibatis.annotations.Param;

public interface MedicationExecutionMapper extends BaseMapper<MedicationExecution> {
    int upsertExecution(@Param("execution") MedicationExecution execution, @Param("operatorId") Long operatorId);
}
