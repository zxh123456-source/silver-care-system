package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.AiCareNoteDraftQuery;
import com.shanzhu.beadhouse.entity.query.SaveCareNoteQuery;
import com.shanzhu.beadhouse.entity.query.FamilyReportQuery;

import java.util.Date;

public interface AiCareService {
    Result draft(AiCareNoteDraftQuery query);
    Result save(SaveCareNoteQuery query);
    Result update(SaveCareNoteQuery query);
    Result handover(Date date);
    Result complete(Long id);
    Result familyReport(FamilyReportQuery query);
}
