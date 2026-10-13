package com.shanzhu.beadhouse.dao.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shanzhu.beadhouse.entity.po.CareAlert;
import org.apache.ibatis.annotations.Param;
public interface CareAlertMapper extends BaseMapper<CareAlert> {
    int upsertEvidence(@Param("alert") CareAlert alert);
    CareAlert lockById(@Param("id") Long id);
    int resolveRecorded(@Param("key") String key);
}
