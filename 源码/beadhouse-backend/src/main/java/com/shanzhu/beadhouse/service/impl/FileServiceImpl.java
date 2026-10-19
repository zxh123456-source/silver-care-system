package com.shanzhu.beadhouse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.file.FileTempPath;
import com.shanzhu.beadhouse.common.config.file.FileUploadConfigPropertity;
import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.common.constant.YesNoEnum;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.dao.mapper.BaseAttachmentMapper;
import com.shanzhu.beadhouse.entity.po.BaseAttachment;
import com.shanzhu.beadhouse.entity.vo.FileInfoVo;
import com.shanzhu.beadhouse.service.FileService;
import com.shanzhu.beadhouse.service.common.AttachmentAccessService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileServiceImpl implements FileService {
    @javax.annotation.Resource private FileTempPath fileTempPath;
    @javax.annotation.Resource private BaseAttachmentMapper baseAttachmentMapper;
    @javax.annotation.Resource private AttachmentAccessService attachmentAccessService;
    @javax.annotation.Resource private FileUploadConfigPropertity fileUploadConfigPropertity;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result save(MultipartFile file, String module) {
        File save = fileTempPath.saveCommFile(file, module);
        try {
            // Covers commit-time failures and rollback of an enclosing transaction as well as insert errors.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        // An unknown commit result may already have persisted the metadata.
                        // Only a confirmed rollback permits deleting the corresponding bytes.
                        if (status == STATUS_ROLLED_BACK) cleanup(save.toPath());
                    }
                });
            }
            BaseAttachment attachment = new BaseAttachment();
            attachment.setName(save.getName());
            attachment.setRealName(file.getOriginalFilename());
            attachment.setPath(save.getAbsolutePath());
            attachment.setUrl("");
            attachment.setSuff(FileUtil.getSuffix(save));
            attachment.setSize(Files.size(save.toPath()));
            attachment.setDelFlag(YesNoEnum.NO.getCode());
            attachment.setBindingState(AttachmentAccessService.DRAFT);
            attachment.setMediaType(Constant.STR_UPLOAD_IMG.equals(module)
                    ? ("png".equalsIgnoreCase(attachment.getSuff()) ? "image/png" : "image/jpeg")
                    : "application/octet-stream");
            if (baseAttachmentMapper.insert(attachment) != 1) throw new IllegalStateException("Attachment was not saved");
            attachment.setUrl(AttachmentAccessService.canonicalUrl(attachment.getId()));
            if (baseAttachmentMapper.updateCanonicalUrl(attachment.getId(), attachment.getUrl()) != 1) {
                throw new IllegalStateException("Attachment URL was not saved");
            }
            return Result.success(BeanUtil.toBean(attachment, FileInfoVo.class));
        } catch (RuntimeException | IOException failure) {
            cleanup(save.toPath());
            throw new BusinessRuntimeException(503, "文件保存失败，请稍后重试");
        }
    }

    @Override
    public ResponseEntity<Resource> content(Long attachmentId) {
        BaseAttachment attachment = attachmentAccessService.assertReadable(attachmentId);
        Path path = fileTempPath.readablePath(attachment.getPath());
        String mediaType = attachment.getMediaType();
        if (mediaType == null) mediaType = fileTempPath.verifiedLegacyImageType(path);
        boolean inline = "image/png".equals(mediaType) || "image/jpeg".equals(mediaType);
        if (!inline) mediaType = "application/octet-stream";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .header("Cache-Control", "private, no-store")
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("Content-Disposition", inline ? "inline" : "attachment; filename=attachment-" + attachmentId)
                .body(new FileSystemResource(path));
    }

    @Override
    public Result resolve(String url) {
        BaseAttachment attachment = attachmentAccessService.resolveReadable(url);
        fileTempPath.readablePath(attachment.getPath());
        FileInfoVo result = new FileInfoVo();
        result.setId(attachment.getId());
        result.setUrl(AttachmentAccessService.canonicalUrl(attachment.getId()));
        return Result.success(result);
    }

    @Override
    public Result diagnostics(long afterId, int limit) {
        attachmentAccessService.assertAdministrator();
        if (afterId < 0 || limit < 1 || limit > 100) {
            throw new BusinessRuntimeException(400, "分页参数无效");
        }
        List<BaseAttachment> attachments = baseAttachmentMapper.selectList(new QueryWrapper<BaseAttachment>()
                .gt("id", afterId).orderByAsc("id").last("LIMIT " + (limit + 1)));
        boolean hasMore = attachments.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        long nextId = afterId;
        for (BaseAttachment attachment : attachments.subList(0, Math.min(limit, attachments.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", attachment.getId());
            item.put("bindingState", attachment.getBindingState());
            item.put("mediaType", attachment.getMediaType());
            item.put("deleted", "Y".equals(attachment.getDelFlag()));
            try {
                fileTempPath.readablePath(attachment.getPath());
                item.put("storageStatus", "AVAILABLE");
            } catch (BusinessRuntimeException failure) {
                item.put("storageStatus", failure.getCode() == 403 ? "OUTSIDE_ROOT"
                        : failure.getCode() == 404 ? "MISSING" : "UNAVAILABLE");
            }
            items.add(item);
            nextId = attachment.getId();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextId", nextId);
        result.put("hasMore", hasMore);
        return Result.success(result);
    }

    private void cleanup(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException failure) { log.warn("Upload cleanup failed ({})", failure.getClass().getSimpleName()); }
    }
}
