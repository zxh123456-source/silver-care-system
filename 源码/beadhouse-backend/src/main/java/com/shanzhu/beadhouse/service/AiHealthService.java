package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.HealthMeasurementQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;

public interface AiHealthService {
    Result addMeasurement(HealthMeasurementQuery query);
    Result trend(Long elderId, Integer limit);
    Result pageElders(PageSearchElderByKeyQuery query);
}
