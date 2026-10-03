package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.OperateSourceQuery;
import com.shanzhu.beadhouse.entity.query.PageSourceByKeyQuery;

public interface SourceService {
    /**
     * 分页查询来源渠道
     *
     * @param pageSourceByKeyQuery
     * @return
     */
    Result pageSourceByKey(PageSourceByKeyQuery pageSourceByKeyQuery);

    /**
     * 新增来源渠道
     *
     * @param sourceName
     * @return
     */
    Result addSource(String sourceName);

    /**
     * 根据编号获取来源渠道
     *
     * @param sourceId
     * @return
     */
    Result getSourceById(Long sourceId);

    /**
     * 编辑来源渠道
     *
     * @param operateSourceQuery
     * @return
     */
    Result editSource(OperateSourceQuery operateSourceQuery);

    /**
     * 删除来源渠道
     *
     * @param sourceId
     * @return
     */
    Result deleteSource(Long sourceId);
}
