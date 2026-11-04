package com.shanzhu.beadhouse.dao.mapper;

import com.shanzhu.beadhouse.entity.vo.CareNotificationVo;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface CareNotificationMapper {
    long countUnread(@Param("staffId") Long staffId, @Param("scopeStaffId") Long scopeStaffId);
    List<CareNotificationVo> listUnread(@Param("staffId") Long staffId, @Param("scopeStaffId") Long scopeStaffId,
                                       @Param("afterId") long afterId, @Param("limit") int limit);
    int markRead(@Param("staffId") Long staffId, @Param("alertId") Long alertId,
                 @Param("revision") Long revision);
    Map<String,Object> scanStatus();
}
