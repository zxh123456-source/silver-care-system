package com.shanzhu.beadhouse.service;

import com.shanzhu.beadhouse.entity.base.Result;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    /**
     * 保存文件信息到数据库
     *
     * @param file
     * @param module
     * @return
     */
    Result save(MultipartFile file, String module);

}
