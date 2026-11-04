package com.shanzhu.beadhouse.common.config.file;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.OsInfo;
import com.shanzhu.beadhouse.common.config.exception.BusinessRuntimeException;
import com.shanzhu.beadhouse.common.constant.Constant;
import com.shanzhu.beadhouse.common.constant.ExceptionEnum;
import com.shanzhu.beadhouse.common.util.AssertUtil;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 文件信息
 */
@Configuration
@AllArgsConstructor
public class FileTempPath {
    private final FileUploadConfigPropertity fileUploadConfigPropertity;

    /**
     * 获取module存储路径
     *
     * @param module 模块名称
     * @return 根路径/module/当天日期/
     */
    public String uploadPath(String module) {
        return getRootPath() + File.separator
                + module + File.separator
                + DateUtil.format(LocalDateTime.now(), DatePattern.PURE_DATE_PATTERN)
                + File.separator;
    }

    /**
     * 下载路径配置
     *
     * @return 根路径/download/
     */
    public String downloadPath() {
        return getRootPath() + File.separator + Constant.STR_DOWNLOAD + File.separator;
    }

    /**
     * 图片路径规则
     *
     * @return 根路径/upload/img/
     */
    public String imgSrcPath() {
        return getRootPath() + File.separator + Constant.STR_UPLOAD_IMG + File.separator;
    }

    /**
     * 视频路径规则
     *
     * @return 根路径/upload/video/
     */
    public String videoSrcPath() {
        return getRootPath() + File.separator + Constant.STR_UPLOAD_VIDEO + File.separator;
    }

    /**
     * 上传附件路径规则
     *
     * @return 根路径/upload/file/
     */
    public String filePath() {
        return getRootPath() + File.separator + Constant.STR_UPLOAD_FILE + File.separator;
    }

    /**
     * 获取根路径
     *
     * @return 根路径
     */
    public String getRootPath() {
        OsInfo osInfo = new OsInfo();
        if (osInfo.isWindows()) {
            return fileUploadConfigPropertity.getWindows();
        } else if (osInfo.isMacOsX()) {
            return fileUploadConfigPropertity.getMacos();
        } else {
            return fileUploadConfigPropertity.getLinux();
        }
    }

    /**
     * 文件上传 保存
     *
     * @param file
     * @param module
     * @param suff
     * @return
     */
    public File saveFile(MultipartFile file, String module, String[] suff) {
        if (file == null || !Arrays.asList(Constant.STR_UPLOAD_IMG, Constant.STR_UPLOAD_FILE, "information").contains(module)) {
            throw invalidUpload("请选择有效文件");
        }
        String original = file.getOriginalFilename();
        if (StrUtil.isBlank(original) || original.length() > 225 || original.contains("/")
                || original.contains("\\") || original.contains(":") || original.chars().anyMatch(Character::isISOControl)) {
            throw invalidUpload("文件名不能包含路径或控制字符");
        }
        String extension = FileUtil.getSuffix(original).toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,12}")) extension = "bin";
        AssertUtil.isTrue(!StrUtil.equalsAnyIgnoreCase("." + extension, suff), ExceptionEnum.UPLOAD_SUFFIX_ERROR);
        boolean image = Constant.STR_UPLOAD_IMG.equals(module);
        long maximum = image ? 2L * 1024 * 1024 : 20L * 1024 * 1024;
        if (file.getSize() <= 0 || file.getSize() > maximum) throw invalidUpload(image ? "图片不能为空且不能超过2MB" : "附件不能为空且不能超过20MB");
        if (image && !Arrays.asList("png", "jpg", "jpeg").contains(extension)) throw invalidUpload("只能上传真实的JPG或PNG图片");

        Path temporary = null;
        try {
            Path root = Paths.get(getRootPath()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            root = root.toRealPath();
            Path directory = safeDirectory(root, module + "/" + DateUtil.format(LocalDateTime.now(), DatePattern.PURE_DATE_PATTERN));
            // Staging files are outside every public resource mapping.
            temporary = Files.createTempFile(safeDirectory(root, ".staging"), "upload-", ".part");
            long total = 0;
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maximum) throw invalidUpload(image ? "图片不能超过2MB" : "附件不能超过20MB");
                    output.write(buffer, 0, count);
                }
            }
            if (total == 0) throw invalidUpload("文件不能为空");
            if (image) validateImage(temporary, extension);
            Path destination = directory.resolve(UUID.randomUUID().toString().replace("-", "") + "." + extension);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            return destination.toFile();
        } catch (IOException failure) {
            throw new BusinessRuntimeException(503, "文件存储暂不可用，请稍后重试");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* The original failure takes precedence. */ }
            }
        }
    }

    private Path safeDirectory(Path root, String relative) throws IOException {
        Path directory = root;
        for (String part : relative.split("/")) {
            directory = directory.resolve(part);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(directory); }
                catch (FileAlreadyExistsException concurrentCreation) { /* Validate the winner's directory below. */ }
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().startsWith(root)) {
                throw invalidUpload("上传目录不可用");
            }
        }
        return directory;
    }

    private void validateImage(Path path, String extension) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidUpload("图片内容无效");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!("png".equals(extension) ? "png".equals(format) : "jpeg".equals(format))) throw invalidUpload("图片类型与文件后缀不一致");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (long) width * height > 16_000_000L) {
                    throw invalidUpload("图片尺寸过大");
                }
                if (reader.read(0) == null) throw invalidUpload("图片内容无效");
            } catch (javax.imageio.IIOException failure) {
                throw invalidUpload("图片内容无效");
            } finally { reader.dispose(); }
        }
    }

    private BusinessRuntimeException invalidUpload(String message) {
        return new BusinessRuntimeException(400, message);
    }

    /** Resolve both paths on disk, so a stored path or symlink cannot escape configured storage. */
    public Path readablePath(String storedPath) {
        if (StrUtil.isBlank(storedPath)) throw new BusinessRuntimeException(404, "文件不存在");
        try {
            Path root = Paths.get(getRootPath()).toAbsolutePath().normalize().toRealPath();
            Path target = Paths.get(storedPath).toAbsolutePath().normalize().toRealPath();
            if (!target.startsWith(root)) throw new BusinessRuntimeException(403, "文件不在存储目录内");
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessRuntimeException(404, "文件不存在");
            }
            return target;
        } catch (NoSuchFileException | InvalidPathException missing) {
            throw new BusinessRuntimeException(404, "文件不存在，请重新上传");
        } catch (IOException unavailable) {
            throw new BusinessRuntimeException(503, "文件存储暂不可用，请稍后重试");
        }
    }

    /** Legacy images have no trusted media metadata. Verify actual bytes before allowing inline display. */
    public String verifiedLegacyImageType(Path path) {
        try {
            long length = Files.size(path);
            if (length <= 0 || length > 2L * 1024 * 1024) return "application/octet-stream";
            try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
                if (input == null) return "application/octet-stream";
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return "application/octet-stream";
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!"png".equals(format) && !"jpeg".equals(format)) return "application/octet-stream";
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                            || (long) width * height > 16_000_000L || reader.read(0) == null) {
                        return "application/octet-stream";
                    }
                    return "png".equals(format) ? "image/png" : "image/jpeg";
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException invalid) {
            return "application/octet-stream";
        }
    }


    /**
     * 文件上传 备查信息
     *
     * @param file http MultipartFile 文件流
     * @param suff 后缀 包含此类型的后缀不允许上传 如：.exe .sh
     * @return
     */
    @SneakyThrows
    public File saveInformationFile(MultipartFile file, String[] suff) {
        return saveFile(file, "information", suff);
    }

    @SneakyThrows
    public File saveCommFile(MultipartFile file, String module) {
        return saveFile(file, module, null);
    }

    /**
     * 下载路径处理 前提是放开uri
     *
     * @param file
     * @return
     */
    public String downloadUrl(File file) {
        try {
            Path root = new File(getRootPath()).getCanonicalFile().toPath();
            Path target = file.getCanonicalFile().toPath();
            if (!target.startsWith(root)) throw invalidUpload("文件不在存储目录内");
            return "/" + root.relativize(target).toString().replace('\\', '/');
        } catch (IOException failure) {
            throw new BusinessRuntimeException(503, "文件存储暂不可用，请稍后重试");
        }
    }

    public String downloadUrl(String path) {
        return downloadUrl(FileUtil.file(path));
    }

}
