package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;

import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import com.shanzhu.beadhouse.entity.vo.DailyCareItemVo;

public interface AiDailyService {
    Result overview(Date date);
    /** Complete, scoped source traversal; independent of overview display limits. */
    void forEachSourcePage(Date day, Consumer<List<DailyCareItemVo>> consumer);
}
