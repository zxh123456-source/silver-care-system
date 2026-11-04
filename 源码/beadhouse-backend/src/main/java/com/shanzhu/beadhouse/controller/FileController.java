package com.shanzhu.beadhouse.controller;

import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.entity.base.Result;
import com.shanzhu.beadhouse.service.FileService;
import io.swagger.annotations.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;

import javax.annotation.Resource;

@Api(tags = "文件上传")
@RestController
@RequestMapping("/file")
public class FileController {
    @Resource
    private FileService fileService;

    @PostMapping("/uploadImg")
    @ApiOperation(value = "单文件上传-图片", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result uploadImg(@ApiParam(value = "图片文件", required = true) @RequestParam(value = "file", required = false) MultipartFile file,
                            @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return fileService.save(file, Constant.STR_UPLOAD_IMG);
    }

    @PostMapping("/uploadFile")
    @ApiOperation(value = "单文件上传-附件", notes = Constant.DEVELOPER + Constant.EMPEROR_WEN)
    public Result uploadFile(@ApiParam(value = "附件文件", required = true) @RequestParam(value = "file", required = false) MultipartFile file,
                             @ApiParam(value = "接口访问请求头", required = true) @RequestHeader String token) {
        return fileService.save(file, Constant.STR_UPLOAD_FILE);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<org.springframework.core.io.Resource> content(@PathVariable Long id) {
        return fileService.content(id);
    }

    @GetMapping("/resolve")
    public ResponseEntity<Result> resolve(@RequestParam String url) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(fileService.resolve(url));
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Result> diagnostics(@RequestParam(defaultValue = "0") long afterId,
                                              @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(fileService.diagnostics(afterId, limit));
    }
}
