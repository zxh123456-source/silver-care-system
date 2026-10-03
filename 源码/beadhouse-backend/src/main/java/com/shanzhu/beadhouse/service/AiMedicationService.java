package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.MedicationExecutionQuery;
import com.shanzhu.beadhouse.entity.query.MedicationPlanQuery;
import com.shanzhu.beadhouse.entity.query.PageSearchElderByKeyQuery;

import java.util.Date;

public interface AiMedicationService {
    Result addPlan(MedicationPlanQuery query);
    Result day(Long elderId, Date date);
    Result execute(MedicationExecutionQuery query);
    Result disablePlan(Long planId);
    Result pageElders(PageSearchElderByKeyQuery query);
}
