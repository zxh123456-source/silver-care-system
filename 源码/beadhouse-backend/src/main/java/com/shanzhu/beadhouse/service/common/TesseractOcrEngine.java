package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.entity.vo.PolicyOcrStatusVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class TesseractOcrEngine {
    private static final List<String> WINDOWS_CANDIDATES = Arrays.asList(
            "C:\\Program Files\\Tesseract-OCR\\tesseract.exe",
            "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe"
    );

    @Value("${ai.ocr.enabled:true}")
    private boolean enabled;
    @Value("${ai.ocr.command:tesseract}")
    private String configuredCommand;
    @Value("${ai.ocr.language:chi_sim+eng}")
    private String language;
    @Value("${ai.ocr.data-dir:}")
    private String dataDir;
    @Value("${ai.ocr.timeout-ms:30000}")
    private long timeoutMs;
    @Value("${ai.ocr.status-cache-ms:30000}")
    private long statusCacheMs;

    private volatile Availability cachedAvailability;
    private volatile long availabilityCheckedAt;

    public PolicyOcrStatusVo status() {
        Availability availability = availability();
        return new PolicyOcrStatusVo(enabled, availability.available, normalizedLanguage(), availability.message);
    }

    public String recognize(BufferedImage image, String context) {
        if (image == null) {
            throw new PolicyDocumentExtractionException(400, "无法读取待识别的图片");
        }
        Availability availability = availability();
        if (!availability.available) {
            throw new PolicyDocumentExtractionException(503, availability.message);
        }

        Path tempDirectory = null;
        try {
            tempDirectory = Files.createTempDirectory("beadhouse-ocr-");
            Path input = tempDirectory.resolve("input.png");
            Path outputBase = tempDirectory.resolve("output");
            Path processLog = tempDirectory.resolve("tesseract.log");
            if (!ImageIO.write(image, "png", input.toFile())) {
                throw new PolicyDocumentExtractionException(400, "无法转换待识别的图片");
            }

            List<String> command = new ArrayList<>();
            command.add(availability.command);
            appendDataDirectory(command);
            command.add(input.toString());
            command.add(outputBase.toString());
            command.add("-l");
            command.add(normalizedLanguage());
            command.add("--psm");
            command.add("6");

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(processLog.toFile());
            Process process = builder.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new PolicyDocumentExtractionException(503,
                        "OCR 识别超时" + label(context) + "，请减小图片或拆分 PDF 后重试");
            }
            if (process.exitValue() != 0) {
                throw new PolicyDocumentExtractionException(503,
                        "OCR 识别失败" + label(context) + "，请确认 Tesseract 和语言数据配置正确");
            }
            Path output = Paths.get(outputBase.toString() + ".txt");
            if (!Files.exists(output)) {
                throw new PolicyDocumentExtractionException(503, "OCR 未生成识别结果" + label(context));
            }
            return normalizeText(new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
        } catch (PolicyDocumentExtractionException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PolicyDocumentExtractionException(503, "OCR 识别被中断" + label(context), exception);
        } catch (IOException exception) {
            invalidateAvailability();
            throw new PolicyDocumentExtractionException(503, "OCR 服务不可用，请检查 Tesseract 配置", exception);
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private Availability availability() {
        if (!enabled) {
            return new Availability(false, null,
                    "OCR 未启用；如需上传扫描 PDF 或图片，请设置 AI_OCR_ENABLED=true");
        }
        long now = System.currentTimeMillis();
        Availability current = cachedAvailability;
        if (current != null && now - availabilityCheckedAt < Math.max(statusCacheMs, 0)) {
            return current;
        }
        synchronized (this) {
            current = cachedAvailability;
            now = System.currentTimeMillis();
            if (current != null && now - availabilityCheckedAt < Math.max(statusCacheMs, 0)) {
                return current;
            }
            current = inspectAvailability();
            cachedAvailability = current;
            availabilityCheckedAt = now;
            return current;
        }
    }

    private Availability inspectAvailability() {
        List<String> candidates = commandCandidates();
        for (String command : candidates) {
            Set<String> installedLanguages = listLanguages(command);
            if (installedLanguages == null) {
                continue;
            }
            List<String> missing = requestedLanguages().stream()
                    .filter(item -> !installedLanguages.contains(item))
                    .collect(Collectors.toList());
            if (!missing.isEmpty()) {
                return new Availability(false, command,
                        "OCR 缺少语言数据：" + String.join("、", missing)
                                + "；请安装对应 Tesseract traineddata 或调整 AI_OCR_LANGUAGE");
            }
            return new Availability(true, command,
                    "OCR 已就绪，可识别扫描 PDF 和图片");
        }
        return new Availability(false, null,
                "未找到 Tesseract OCR；请安装 Tesseract 或设置 AI_OCR_COMMAND");
    }

    private List<String> commandCandidates() {
        List<String> candidates = new ArrayList<>();
        String command = trim(configuredCommand);
        if (!command.isEmpty()) {
            candidates.add(command);
        }
        if (isWindows() && (command.isEmpty() || "tesseract".equalsIgnoreCase(command))) {
            for (String candidate : WINDOWS_CANDIDATES) {
                if (Files.isRegularFile(Paths.get(candidate)) && !candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private Set<String> listLanguages(String command) {
        Path output = null;
        try {
            output = Files.createTempFile("beadhouse-tesseract-langs-", ".log");
            List<String> args = new ArrayList<>();
            args.add(command);
            appendDataDirectory(args);
            args.add("--list-langs");
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.redirectErrorStream(true);
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
            if (!process.waitFor(Math.min(Math.max(timeoutMs, 1000), 5000), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            Set<String> result = new HashSet<>();
            for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (!value.isEmpty() && !value.toLowerCase(Locale.ROOT).startsWith("list of available")) {
                    result.add(value);
                }
            }
            return result;
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // Temporary files are also cleaned by the operating system.
                }
            }
        }
    }

    private void appendDataDirectory(List<String> command) {
        String directory = trim(dataDir);
        if (!directory.isEmpty()) {
            command.add("--tessdata-dir");
            command.add(directory);
        }
    }

    private List<String> requestedLanguages() {
        String configured = normalizedLanguage();
        if (configured.isEmpty()) {
            return Collections.singletonList("eng");
        }
        return Arrays.stream(configured.split("\\+"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    private String normalizedLanguage() {
        String value = trim(language);
        return value.isEmpty() ? "eng" : value;
    }

    private void invalidateAvailability() {
        cachedAvailability = null;
        availabilityCheckedAt = 0;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String label(String context) {
        String value = trim(context);
        return value.isEmpty() ? "" : "（" + value + "）";
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Collections.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {
            // Best effort cleanup only.
        }
    }

    private static final class Availability {
        private final boolean available;
        private final String command;
        private final String message;

        private Availability(boolean available, String command, String message) {
            this.available = available;
            this.command = command;
            this.message = message;
        }
    }
}
