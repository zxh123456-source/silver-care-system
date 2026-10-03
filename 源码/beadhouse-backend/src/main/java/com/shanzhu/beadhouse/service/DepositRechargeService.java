package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PageDepositRechargeByKeyQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;
import com.shanzhu.beadhouse.entity.query.RechargeQuery;

public interface DepositRechargeService {
    /**
     * 分页查询预存充值
     *
     * @param pageDepositRechargeByKeyQuery
     * @return
     */
    Result pageDepositRechargeByKey(PageDepositRechargeByKeyQuery pageDepositRechargeByKeyQuery);

    /**
     * 分页搜索老人
     *
     * @param pageSearchElderByKeyQuery
     * @return
     */
    Result pageSearchElderByKey(PageSearchElderByKeyQuery pageSearchElderByKeyQuery);

    /**
     * 入住老人账户充值
     *
     * @param rechargeQuery
     * @return
     */
    Result recharge(RechargeQuery rechargeQuery);
}
