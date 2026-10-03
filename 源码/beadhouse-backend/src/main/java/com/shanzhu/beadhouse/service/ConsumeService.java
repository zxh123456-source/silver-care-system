package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PageConsumeByKeyQuery;

public interface ConsumeService {
    /**
     * 分页查询消费记录
     *
     * @param pageConsumeByKeyQuery
     * @return
     */
    Result pageConsumeByKey(PageConsumeByKeyQuery pageConsumeByKeyQuery);
}
