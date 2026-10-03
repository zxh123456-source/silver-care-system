package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PageRetreatApplyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;

public interface RetreatApplyService {
    /**
     * 分页查询退住申请
     *
     * @param pageRetreatApplyQuery
     * @return
     */
    Result pageRetreatApplyByKey(PageRetreatApplyQuery pageRetreatApplyQuery);

    /**
     * 分页搜索老人
     *
     * @param pageSearchElderByKeyQuery
     * @return
     */
    Result pageSearchElderByKey(PageSearchElderByKeyQuery pageSearchElderByKeyQuery);

    /**
     * 新增退住申请
     *
     * @param elderId
     * @return
     */
    Result addRetreatApply(Long elderId);
}
