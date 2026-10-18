package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
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
import java.io.IOException;
import java.nio.file.Files;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
        File save = fileTempPath.saveCommFile(file, module);
        try {
            String head = fileUploadConfigPropertity.getUploadHead();
            String downloadUrl = (head == null ? "" : head.replaceAll("/+$", "")) + fileTempPath.downloadUrl(save);
            BaseAttachment attachment = new BaseAttachment();
            attachment.setName(save.getName());
            attachment.setRealName(file.getOriginalFilename());
            attachment.setPath(save.getAbsolutePath());
            attachment.setUrl(downloadUrl);
            attachment.setSuff(FileUtil.getSuffix(save));
            attachment.setSize(Files.size(save.toPath()));
            attachment.setDelFlag(YesNoEnum.NO.getCode());
            if (baseAttachmentMapper.insert(attachment) != 1) throw new IllegalStateException("Attachment was not saved");
            return Result.success(BeanUtil.toBean(attachment, FileInfoVo.class));
        } catch (RuntimeException | IOException failure) {
            try { Files.deleteIfExists(save.toPath()); }
            catch (IOException cleanupFailure) { log.warn("Upload cleanup failed ({})", cleanupFailure.getClass().getSimpleName()); }
            throw new BusinessRuntimeException(503, "文件保存失败，请稍后重试");
        }
    }
}
