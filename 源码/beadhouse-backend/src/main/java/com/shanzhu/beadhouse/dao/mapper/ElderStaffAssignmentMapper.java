package com.shanzhu.beadhouse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.ElderStaffAssignment;
import org.apache.ibatis.annotations.Param;

public interface ElderStaffAssignmentMapper extends BaseMapper<ElderStaffAssignment> {
    int upsertAssignment(@Param("elderId") Long elderId, @Param("staffId") Long staffId, @Param("operatorId") Long operatorId);
}
