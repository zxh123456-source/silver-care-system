package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.OperateActiveQuery;
import com.shanzhu.beadhouse.entity.query.PageActiveByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;

public interface ActiveService {
    /**
     * 获取活动类型
     *
     * @return
     */
    Result getActiveType();

    /**
     * 分页查询活动
     *
     * @param pageActiveByKeyQuery
     * @return
     */
    Result pageActiveByKey(PageActiveByKeyQuery pageActiveByKeyQuery);

    /**
     * 分页搜索老人
     *
     * @param pageSearchElderByKeyQuery
     * @return
     */
    Result pageSearchElderByKey(PageSearchElderByKeyQuery pageSearchElderByKeyQuery);

    /**
     * 新增活动
     *
     * @param operateActiveQuery
     * @return
     */
    Result addActive(OperateActiveQuery operateActiveQuery);

    /**
     * 根据编号查询活动
     *
     * @param activeId
     * @return
     */
    Result getActiveById(Long activeId);

    /**
     * 编辑活动
     *
     * @param operateActiveQuery
     * @return
     */
    Result editActive(OperateActiveQuery operateActiveQuery);

    /**
     * 删除活动
     *
     * @param activeId
     * @return
     */
    Result deleteActive(Long activeId);
}
