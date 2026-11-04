package com.shanzhu.beadhouse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import org.apache.ibatis.annotations.Param;

public interface PolicyRagSyncMapper extends BaseMapper<PolicyRagSync> {
    int initialize(@Param("key") String key,@Param("title") String title,@Param("source") String source,@Param("actor") Long actor);
    PolicyRagSync lockDocument(@Param("key") String key);
    PolicyRagSync lockDue(@Param("key") String key);
    int stage(@Param("row") PolicyRagSync row,@Param("previousVersion") long previousVersion,@Param("actor") Long actor);
    int claim(@Param("id") Long id,@Param("owner") String owner,@Param("seconds") int seconds);
    int validClaim(@Param("claim") PolicyRagSync claim);
    int markSynced(@Param("claim") PolicyRagSync claim);
    int markFailed(@Param("claim") PolicyRagSync claim,@Param("errorCode") String errorCode);
    int systemAudit(@Param("id") Long id,@Param("detail") String detail);
}
