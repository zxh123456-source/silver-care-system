package com.shanzhu.beadhouse.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.PolicyRagSync;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PolicyRagSyncMapper extends BaseMapper<PolicyRagSync> {
    int upsertDesired(@Param("documentKey") String documentKey,
                      @Param("title") String title,
                      @Param("source") String source,
                      @Param("desiredAction") String desiredAction,
                      @Param("revision") String revision);
    List<PolicyRagSync> listDue(@Param("limit") Integer limit);
    int markSynced(@Param("documentKey") String documentKey,
                   @Param("desiredAction") String desiredAction,
                   @Param("revision") String revision);
    int markFailed(@Param("documentKey") String documentKey,
                   @Param("desiredAction") String desiredAction,
                   @Param("revision") String revision,
                   @Param("errorCode") String errorCode);
}
