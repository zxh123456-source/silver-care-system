package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PageElderByKeyQuery;

import java.io.IOException;

public interface ElderRecordService {
    /**
     * 导出excel
     */
    Result exportExcel() throws IOException;

    /**
     * 分页查询长者
     *
     * @param pageElderByKeyQuery
     * @return
     */
    Result pageElderByKey(PageElderByKeyQuery pageElderByKeyQuery);

    /**
     * 根据编号获取长者档案
     *
     * @param elderId
     * @return
     */
    Result getElderRecordById(Long elderId);
}
