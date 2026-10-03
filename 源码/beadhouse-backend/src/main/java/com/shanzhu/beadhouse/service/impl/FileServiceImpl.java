package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrPool;
import com.shanzhu.beadhouse.common.config.file.FileTempPath;
import com.shanzhu.beadhouse.common.config.file.FileUploadConfigPropertity;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.BaseAttachmentMapper;
import com.shanzhu.beadhouse.entity.po.BaseAttachment;
import com.shanzhu.beadhouse.entity.vo.FileInfoVo;
import com.shanzhu.beadhouse.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;

@Service
public class FileServiceImpl implements FileService {
    @Resource
    private FileTempPath fileTempPath;

    @Resource
    private BaseAttachmentMapper baseAttachmentMapper;

    @Resource
    private FileUploadConfigPropertity fileUploadConfigPropertity;

    @Override
    public Result save(MultipartFile file, String module) {
        long size = file.getSize();
        String originalFilename = file.getOriginalFilename();
        File save = fileTempPath.saveCommFile(file, module);
        String filePath = save.getAbsolutePath();
        String downloadUrl = fileUploadConfigPropertity.getUploadHead() + filePath
                .replace(fileTempPath.getRootPath(), "")
                .replace(StrPool.BACKSLASH, StrPool.SLASH);

        BaseAttachment baseAttachment = new BaseAttachment();
        baseAttachment.setName(save.getName());
        baseAttachment.setRealName(originalFilename);
        baseAttachment.setPath(save.getAbsolutePath());
        baseAttachment.setUrl(downloadUrl);
        baseAttachment.setSuff(FileUtil.getSuffix(save));
        baseAttachment.setSize(size);
        baseAttachment.setDelFlag(YesNoEnum.NO.getCode());
        baseAttachmentMapper.insert(baseAttachment);

        return Result.success(BeanUtil.toBean(baseAttachment, FileInfoVo.class));
    }
}
