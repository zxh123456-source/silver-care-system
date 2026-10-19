package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

public interface FileService {

    /**
     * 保存文件信息到数据库
     *
     * @param file
     * @param module
     * @return
     */
    Result save(MultipartFile file, String module);

    ResponseEntity<Resource> content(Long attachmentId);

    Result resolve(String url);

    Result diagnostics(long afterId, int limit);

}
