package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.dao.mapper.ElderMapper;
import com.shanzhu.beadhouse.entity.base.PageResult;
import com.shanzhu.beadhouse.entity.query.PageElderByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.vo.PageElderByKeyVo;
import com.shanzhu.beadhouse.entity.vo.PageSearchElderByKeyVo;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.List;

/** Bounded elder reads. Scope identity and eligibility are supplied by authorized services. */
@Service
public class ElderPageService {
    @Resource private ElderMapper elderMapper;

    public PageResult<PageSearchElderByKeyVo> selector(PageSearchElderByKeyQuery request,List<String> states,
            Long scopeStaffId,boolean excludeOutward,boolean excludePendingRetreat) {
        PageSearchElderByKeyQuery query=request==null ? new PageSearchElderByKeyQuery() : request;
        int page=page(query.getPageNum()), size=size(query.getPageSize());
        long offset=(long)(page-1)*size;
        long total=elderMapper.countScopedElders(query.getName(),query.getPhone(),states,scopeStaffId,
                excludeOutward,excludePendingRetreat);
        List<PageSearchElderByKeyVo> rows=elderMapper.pageScopedElders(query.getName(),query.getPhone(),states,
                scopeStaffId,excludeOutward,excludePendingRetreat,offset,size);
        for(int i=0;i<rows.size();i++) rows.get(i).setRank(offset+i+1);
        return new PageResult<>(page,size,total,rows);
    }

    public PageResult<PageElderByKeyVo> records(PageElderByKeyQuery request,Long scopeStaffId) {
        PageElderByKeyQuery query=request==null ? new PageElderByKeyQuery() : request;
        int page=page(query.getPageNum()), size=size(query.getPageSize());
        long offset=(long)(page-1)*size;
        long total=elderMapper.countElderRecords(query,scopeStaffId);
        List<PageElderByKeyVo> rows=elderMapper.pageElderRecords(query,scopeStaffId,offset,size);
        for(int i=0;i<rows.size();i++) rows.get(i).setRank(offset+i+1);
        return new PageResult<>(page,size,total,rows);
    }
    private int page(Integer value) { return value==null || value<1 ? 1 : value; }
    private int size(Integer value) { return value==null || value<1 ? 10 : Math.min(value,100); }
}
