package com.shanzhu.beadhouse.service.impl;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.config.file.FileTempPath;
import com.shanzhu.beadhouse.common.config.file.FileUploadConfigPropertity;
import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.dao.mapper.BaseAttachmentMapper;
import com.shanzhu.beadhouse.entity.po.BaseAttachment;
import com.shanzhu.beadhouse.entity.vo.FileInfoVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileUploadPersistenceTest {
    @TempDir Path temporary;
    private FileServiceImpl service;
    private BaseAttachmentMapper mapper;

    @BeforeEach void setup() {
        FileUploadConfigPropertity config = new FileUploadConfigPropertity();
        config.setWindows(temporary.toString()); config.setLinux(temporary.toString()); config.setMacos(temporary.toString());
        config.setUploadHead("http://127.0.0.1:9001/");
        service = new FileServiceImpl();
        mapper = mock(BaseAttachmentMapper.class);
        ReflectionTestUtils.setField(service, "fileTempPath", new FileTempPath(config));
        ReflectionTestUtils.setField(service, "fileUploadConfigPropertity", config);
        ReflectionTestUtils.setField(service, "baseAttachmentMapper", mapper);
    }

    @Test void databaseFailureRemovesTheJustUploadedFile() throws Exception {
        when(mapper.insert(any())).thenThrow(new IllegalStateException("private database details"));
        assertThatThrownBy(() -> service.save(file(), Constant.STR_UPLOAD_FILE))
                .isInstanceOfSatisfying(BusinessRuntimeException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503);
                    assertThat(error.getMessage()).doesNotContain("private database");
                });
        try (Stream<Path> files = Files.walk(temporary)) {
            assertThat(files.filter(Files::isRegularFile).count()).isZero();
        }
    }

    @Test void successfulUploadKeepsOriginalNameOnlyInMetadata() throws Exception {
        when(mapper.insert(any())).thenReturn(1);
        FileInfoVo info = (FileInfoVo) service.save(file(), Constant.STR_UPLOAD_FILE).getData();
        assertThat(info.getUrl()).matches("http://127\\.0\\.0\\.1:9001/upload/file/[0-9]{8}/[a-f0-9]{32}\\.txt");
        ArgumentCaptor<BaseAttachment> captured = ArgumentCaptor.forClass(BaseAttachment.class);
        verify(mapper).insert(captured.capture());
        assertThat(captured.getValue().getRealName()).isEqualTo("护理说明.txt");
        assertThat(Files.readAllBytes(Paths.get(captured.getValue().getPath()))).isEqualTo(new byte[]{1, 2, 3});
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "护理说明.txt", "text/plain", new byte[]{1, 2, 3});
    }
}
