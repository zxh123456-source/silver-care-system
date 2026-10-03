package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.AddAccidentQuery;
import com.shanzhu.beadhouse.entity.query.EditAccidentQuery;
import com.shanzhu.beadhouse.entity.query.PageAccidentByKeyQuery;

public interface AccidentService {
    /**
     * 分页查询事故登记
     *
     * @param pageAccidentByKeyQuery
     * @return
     */
    Result pageAccidentByKey(PageAccidentByKeyQuery pageAccidentByKeyQuery);

    /**
     * 新增事故登记
     *
     * @param addAccidentQuery
     * @return
     */
    Result addAccident(AddAccidentQuery addAccidentQuery);

    /**
     * 根据编号获取事故登记
     *
     * @param accidentId
     * @return
     */
    Result getAccidentById(Long accidentId);

    /**
     * 编辑事故登记
     *
     * @param editAccidentQuery
     * @return
     */
    Result editAccident(EditAccidentQuery editAccidentQuery);

    /**
     * 删除事故登记
     *
     * @param accidentId
     * @return
     */
    Result deleteAccident(Long accidentId);
}
