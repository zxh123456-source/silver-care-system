package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.security.handler.AuthorityAssert;
import com.shanzhu.beadhouse.dao.mapper.CareAlertMapper;
import com.shanzhu.beadhouse.dao.mapper.CareNotificationMapper;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.po.CareAlert;
import com.shanzhu.beadhouse.entity.query.CareNotificationReadQuery;
import com.shanzhu.beadhouse.entity.vo.CareNotificationVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;

@Service
public class CareNotificationService {
    @Resource private CareNotificationMapper mapper;
    @Resource private CareAlertMapper alertMapper;
    @Resource private AiDataScopeService scope;
    @Resource private AuthorityAssert authority;
    @Value("${ai.alert.scan.enabled:true}") private boolean scanEnabled;

    public Result count() {
        Long scopeStaffId=scope.queryStaffId();
        return Result.success(Collections.singletonMap("unreadCount",
                mapper.countUnread(authority.getLoginUserId(),scopeStaffId)));
    }

    public Result list(Long afterId,Integer limit) {
        long cursor=afterId==null?0:afterId;
        int size=limit==null?20:limit;
        if(cursor<0 || size<1 || size>100) return Result.error(400,"游标须非负，每次读取1至100条提醒");
        Long scopeStaffId=scope.queryStaffId();
        Long staff=authority.getLoginUserId();
        List<CareNotificationVo> rows=mapper.listUnread(staff,scopeStaffId,cursor,size+1);
        boolean hasMore=rows.size()>size;
        List<CareNotificationVo> items=hasMore?new ArrayList<>(rows.subList(0,size)):rows;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("unreadCount",mapper.countUnread(staff,scopeStaffId));
        result.put("items",items);
        result.put("nextCursor",items.isEmpty()?cursor:items.get(items.size()-1).getId());
        result.put("hasMore",hasMore);
        return Result.success(result);
    }

    @Transactional
    public Result read(CareNotificationReadQuery query) {
        scope.queryStaffId();
        if(query==null || query.getId()==null || query.getId()<1 || query.getNoticeRevision()==null
                || query.getNoticeRevision()<1) return Result.error(400,"提醒编号和通知版本不能为空");
        CareAlert alert=alertMapper.lockById(query.getId());
        if(alert==null) throw new BusinessRuntimeException(404,"提醒不存在");
        scope.assertElderAccess(alert.getElderId());
        if(!Objects.equals(query.getNoticeRevision(),alert.getNoticeRevision()))
            return Result.error(409,"提醒已有新证据，请刷新后再标记已读");
        mapper.markRead(authority.getLoginUserId(),alert.getId(),alert.getNoticeRevision());
        // Reading never acknowledges or resolves the underlying care alert.
        return Result.success();
    }

    public Result scanStatus() {
        scope.queryStaffId();
        Map<String,Object> stored=mapper.scanStatus();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("enabled",scanEnabled);
        result.put("state",stored==null?"IDLE":stored.get("state"));
        // Operational dates only: never publish global counts, cursor IDs, lease tokens or raw errors.
        for(String key:Arrays.asList("scanDate","targetDate","lastCompletedDate","lastSuccessAt","nextScanAt"))
            result.put(key,stored==null?null:stored.get(key));
        return Result.success(result);
    }
}
