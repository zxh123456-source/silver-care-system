package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.OperateActiveTypeQuery;
import com.shanzhu.beadhouse.entity.query.PageActiveTypeByKeyQuery;

public interface ActiveTypeService {
    /**
     * 分页查询活动分类
     *
     * @param pageActiveTypeByKeyQuery
     * @return
     */
    Result pageActiveTypeByKey(PageActiveTypeByKeyQuery pageActiveTypeByKeyQuery);

    /**
     * 新增活动分类
     *
     * @param activeTypeName
     * @return
     */
    Result addActiveType(String activeTypeName);

    /**
     * 根据编号获取活动分类
     *
     * @param activeTypeId
     * @return
     */
    Result getActiveTypeById(Long activeTypeId);

    /**
     * 编辑活动分类
     *
     * @param operateActiveTypeQuery
     * @return
     */
    Result editActiveType(OperateActiveTypeQuery operateActiveTypeQuery);

    /**
     * 删除活动分类
     *
     * @param activeTypeId
     * @return
     */
    Result deleteActiveType(Long activeTypeId);
}
