package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.entity.query.PolicyImportQuery;
import com.shanzhu.beadhouse.entity.query.PolicyQuery;
import com.shanzhu.beadhouse.service.AiPolicyService;
import com.shanzhu.beadhouse.service.common.PolicyDocumentExtractionException;
import com.shanzhu.beadhouse.service.common.PolicyDocumentTextExtractor;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.annotation.Resource;

@Api(tags = "AI 制度知识库")
@RestController
@RequestMapping("/ai/policy")
@PreAuthorize("@AuthorityAssert.hasAuthority('/ai/policy/index')")
public class AiPolicyController {
    @Resource
    private AiPolicyService aiPolicyService;
    @Resource
    private PolicyDocumentTextExtractor documentTextExtractor;

    @PostMapping("/import")
    @ApiOperation(value = "导入制度文档", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result importPolicy(@RequestBody PolicyImportQuery query, @RequestHeader String token) {
        return aiPolicyService.importPolicy(query);
    }

    @PostMapping("/query")
    @ApiOperation(value = "检索制度知识库", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result query(@RequestBody PolicyQuery query, @RequestHeader String token) {
        return aiPolicyService.query(query);
    }

    @GetMapping("/documents")
    @ApiOperation(value = "查询已导入制度", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result listDocuments(@RequestHeader String token) {
        return aiPolicyService.listDocuments();
    }

    @DeleteMapping("/document")
    @ApiOperation(value = "删除制度文档", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result deleteDocument(@RequestParam String title, @RequestParam String source,
                                 @RequestHeader String token) {
        return aiPolicyService.deleteDocument(title, source);
    }

    @PostMapping("/upload")
    @ApiOperation(value = "上传制度文档", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result upload(@RequestParam("file") MultipartFile file, @RequestHeader String token) {
        if (file == null || file.isEmpty()) return Result.error(400, "请选择制度文件");
        String filename = file.getOriginalFilename() == null ? "制度文件" : file.getOriginalFilename();
        String lowerName = filename.toLowerCase();
        if (!(lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".pdf")
                || lowerName.endsWith(".docx") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".bmp"))) {
            return Result.error(400, "当前支持 txt、md、pdf、docx、png、jpg 和 bmp 文件");
        }
        if (file.getSize() > 10 * 1024 * 1024) return Result.error(400, "制度文件不能超过 10MB");
        try {
            PolicyDocumentTextExtractor.ExtractionResult extraction = documentTextExtractor.extract(file, lowerName);
            String title = filename.replaceFirst("(?i)\\.(txt|md|pdf|docx|png|jpe?g|bmp)$", "");
            PolicyImportQuery query = new PolicyImportQuery();
            query.setTitle(title);
            query.setSource(filename);
            query.setContent(extraction.getText());
            Result result = aiPolicyService.importPolicy(query);
            if (extraction.isOcrUsed() && result.getCode() != null && result.getCode() == 200) {
                result.setMsg("OCR 识别完成，" + result.getMsg());
            }
            return result;
        } catch (PolicyDocumentExtractionException exception) {
            return Result.error(exception.getCode(), exception.getMessage());
        }
    }

    @GetMapping("/ocr/status")
    @ApiOperation(value = "查询扫描文档 OCR 状态", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result ocrStatus(@RequestHeader String token) {
        return Result.success(documentTextExtractor.ocrStatus());
    }

    @PostMapping("/reindex")
    @ApiOperation(value = "重建制度混合检索索引", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result reindex(@RequestHeader String token) {
        return aiPolicyService.reindex();
    }

}
