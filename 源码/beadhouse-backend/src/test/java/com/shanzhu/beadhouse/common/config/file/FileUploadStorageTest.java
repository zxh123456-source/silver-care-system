package com.shanzhu.beadhouse.common.config.file;

import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.constant.Constant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.stream.Stream;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.*;

class FileUploadStorageTest {
    @TempDir Path temporary;
    private Path root;
    private FileTempPath storage;

    @BeforeEach void setup() {
        root = temporary.resolve("storage");
        FileUploadConfigPropertity config = new FileUploadConfigPropertity();
        config.setWindows(root.toString());
        config.setLinux(root.toString());
        config.setMacos(root.toString());
        storage = new FileTempPath(config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nested/../../../../../sentinel.txt", "nested\\..\\..\\..\\..\\..\\sentinel.txt",
            "/absolute.txt", "C:\\absolute.txt", "name:stream.txt", "line\r\nbreak.txt", "../photo.png"})
    void rejectsUntrustedFilenamePathsBeforeWriting(String filename) throws Exception {
        Path sentinel = temporary.resolve("sentinel.txt");
        Files.write(sentinel, "untouched".getBytes("UTF-8"));
        invalid(() -> storage.saveCommFile(new MockMultipartFile("file", filename, "text/plain", "attack".getBytes("UTF-8")), Constant.STR_UPLOAD_FILE));
        assertThat(new String(Files.readAllBytes(sentinel), "UTF-8")).isEqualTo("untouched");
        assertNoFiles();
    }

    @Test void storesValidImageWithOpaqueNameAndPortableRelativeUrl() throws Exception {
        byte[] png = png();
        File saved = storage.saveCommFile(new MockMultipartFile("file", "护理 照片.PNG", "image/png", png), Constant.STR_UPLOAD_IMG);
        assertThat(saved.toPath().toRealPath()).startsWith(root.toRealPath());
        assertThat(saved.getName()).matches("[a-f0-9]{32}\\.png");
        assertThat(Files.readAllBytes(saved.toPath())).isEqualTo(png);
        assertThat(storage.downloadUrl(saved)).matches("/upload/img/[0-9]{8}/[a-f0-9]{32}\\.png");
    }

    @Test void rejectsForgedImagesEmptyFilesAndUnsupportedModules() throws Exception {
        invalid(() -> storage.saveCommFile(new MockMultipartFile("file", "fake.png", "image/png", "<script>bad()</script>".getBytes("UTF-8")), Constant.STR_UPLOAD_IMG));
        invalid(() -> storage.saveCommFile(new MockMultipartFile("file", "photo.svg", "image/svg+xml", "<svg/>".getBytes("UTF-8")), Constant.STR_UPLOAD_IMG));
        invalid(() -> storage.saveCommFile(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]), Constant.STR_UPLOAD_IMG));
        invalid(() -> storage.saveCommFile(null, Constant.STR_UPLOAD_IMG));
        invalid(() -> storage.saveCommFile(new MockMultipartFile("file", "photo.png", "image/png", png()), "../../outside"));
        assertNoFiles();
    }

    @Test void enforcesImageLimitEvenWhenMultipartSizeIsIncorrect() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "large.png", "image/png", new byte[2 * 1024 * 1024 + 1]) {
            @Override public long getSize() { return 1; }
        };
        invalid(() -> storage.saveCommFile(file, Constant.STR_UPLOAD_IMG));
        assertNoFiles();
    }

    @Test void cleansPartialWritesAndReturnsGenericStorageFailure() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "document.txt", "text/plain", new byte[]{1}) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    int reads;
                    @Override public int read() throws IOException {
                        if (reads++ < 8) return 1;
                        throw new IOException("private filesystem details");
                    }
                };
            }
            @Override public void transferTo(File destination) throws IOException {
                Files.write(destination.toPath(), new byte[]{1});
                throw new IOException("private filesystem details");
            }
        };
        assertThatThrownBy(() -> storage.saveCommFile(file, Constant.STR_UPLOAD_FILE))
                .isInstanceOfSatisfying(BusinessRuntimeException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(503);
                    assertThat(error.getMessage()).doesNotContain("private filesystem");
                });
        assertNoFiles();
    }

    @Test void refusesUrlsOutsideTheConfiguredStorageRoot() {
        invalid(() -> storage.downloadUrl(temporary.resolve("outside.txt").toFile()));
    }

    @Test void verifiesImageFormatAndDimensionsBeforeDecodingPixels() throws Exception {
        invalid(() -> storage.saveCommFile(new MockMultipartFile("file", "mismatch.jpg", "image/jpeg", png()), Constant.STR_UPLOAD_IMG));
        byte[] oversized = png();
        ByteBuffer.wrap(oversized).putInt(16, 100_000).putInt(20, 100_000);
        CRC32 checksum = new CRC32(); checksum.update(oversized, 12, 17);
        ByteBuffer.wrap(oversized).putInt(29, (int) checksum.getValue());
        assertThatThrownBy(() -> storage.saveCommFile(new MockMultipartFile("file", "huge.png", "image/png", oversized), Constant.STR_UPLOAD_IMG))
                .isInstanceOfSatisfying(BusinessRuntimeException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo("图片尺寸过大");
                });
        assertNoFiles();
    }

    @Test void limitsAttachmentSizeAndMetadataFilenameLength() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", new byte[]{1}) {
            @Override public long getSize() { return 20L * 1024 * 1024 + 1; }
        };
        invalid(() -> storage.saveCommFile(file, Constant.STR_UPLOAD_FILE));
        char[] name = new char[226]; Arrays.fill(name, 'a');
        invalid(() -> storage.saveCommFile(new MockMultipartFile("file", new String(name), "text/plain", new byte[]{1}), Constant.STR_UPLOAD_FILE));
        assertNoFiles();
    }

    @Test void simultaneousFirstUploadsNeverOverwriteOrFailDirectoryCreation() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<File>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) results.add(workers.submit(() -> {
                start.await();
                return storage.saveCommFile(new MockMultipartFile("file", "same.txt", "text/plain", new byte[]{1, 2}), Constant.STR_UPLOAD_FILE);
            }));
            start.countDown();
            Set<Path> saved = new HashSet<>();
            for (Future<File> result : results) {
                Path path = result.get(10, TimeUnit.SECONDS).toPath();
                assertThat(Files.readAllBytes(path)).isEqualTo(new byte[]{1, 2});
                saved.add(path);
            }
            assertThat(saved).hasSize(8);
        } finally { workers.shutdownNow(); }
    }

    private void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessRuntimeException.class,
                error -> assertThat(error.getCode()).isEqualTo(400));
    }

    private void assertNoFiles() throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).count()).isZero();
        }
    }

    private byte[] png() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
}
