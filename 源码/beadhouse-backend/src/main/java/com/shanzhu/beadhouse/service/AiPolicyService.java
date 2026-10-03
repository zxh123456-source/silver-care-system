package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PolicyImportQuery;
import com.shanzhu.beadhouse.entity.query.PolicyQuery;

public interface AiPolicyService {
    Result importPolicy(PolicyImportQuery query);
    Result query(PolicyQuery query);
    Result listDocuments();
    Result deleteDocument(String title, String source);
    Result reindex();
}
