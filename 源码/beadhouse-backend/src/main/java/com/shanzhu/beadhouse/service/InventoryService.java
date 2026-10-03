package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PageInventoryByKeyQuery;

public interface InventoryService {
    /**
     * 分页查询库存
     *
     * @param pageInventoryByKeyQuery
     * @return
     */
    Result pageInventoryByKey(PageInventoryByKeyQuery pageInventoryByKeyQuery);
}
