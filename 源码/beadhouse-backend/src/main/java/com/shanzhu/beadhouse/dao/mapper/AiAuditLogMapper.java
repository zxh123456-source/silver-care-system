package com.shanzhu.beadhouse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.AiAuditLog;
import com.shanzhu.beadhouse.entity.vo.AiAuditLogVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AiAuditLogMapper extends BaseMapper<AiAuditLog> {
    Long countAuditLogs();
    List<AiAuditLogVo> listAuditLogs(@Param("offset") Integer offset, @Param("pageSize") Integer pageSize);
}
