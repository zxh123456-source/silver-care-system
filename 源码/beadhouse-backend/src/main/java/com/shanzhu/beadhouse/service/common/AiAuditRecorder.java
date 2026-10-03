package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.AiAuditLogMapper;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.po.AiAuditLog;
import com.shanzhu.beadhouse.entity.vo.AiAuditLogVo;
import com.shanzhu.beadhouse.entity.vo.LoginUserVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Component
public class AiAuditRecorder {
    @Resource
    private AiAuditLogMapper auditLogMapper;
    @Resource
    private AuthorityAssert authorityAssert;

    public void record(String module, String action, String objectType, Long objectId, String safeDetail) {
        try {
            LoginUserVo user = authorityAssert.getLoginUserInfo();
            AiAuditLog log = new AiAuditLog();
            log.setModule(module);
            log.setAction(action);
            log.setObjectType(objectType);
            log.setObjectId(objectId);
            log.setOperatorId(user == null ? null : user.getId());
            log.setOperatorName(user == null ? "系统" : user.getName());
            log.setDetail(truncate(safeDetail));
            auditLogMapper.insert(log);
        } catch (Exception exception) {
            log.warn("AI audit write failed: module={}, action={}, objectType={}, objectId={}, exception={}",
                    module, action, objectType, objectId, exception.getClass().getSimpleName());
        }
    }

    public PageResult<AiAuditLogVo> page(Integer pageNum, Integer pageSize) {
        int safePageNum = pageNum == null ? 1 : Math.max(1, pageNum);
        int safePageSize = pageSize == null ? 20 : Math.max(1, Math.min(pageSize, 100));
        Long total = auditLogMapper.countAuditLogs();
        List<AiAuditLogVo> list = auditLogMapper.listAuditLogs((safePageNum - 1) * safePageSize, safePageSize);
        return new PageResult<>(safePageNum, safePageSize, total, list);
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
