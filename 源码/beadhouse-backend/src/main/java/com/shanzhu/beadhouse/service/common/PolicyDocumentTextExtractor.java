package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.entity.vo.PolicyOcrStatusVo;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class PolicyDocumentTextExtractor {
    private static final long LARGE_PDF_IMAGE_PIXELS = 300_000L;

    @Resource
    private TesseractOcrEngine ocrEngine;
    @Value("${ai.ocr.pdf-dpi:160}")
    private float pdfDpi;
    @Value("${ai.ocr.max-pdf-pages:30}")
    private int maxPdfPages;
    @Value("${ai.ocr.max-image-pixels:25000000}")
    private long maxImagePixels;
    @Value("${ai.ocr.document-timeout-ms:120000}")
    private long documentTimeoutMs;

    public ExtractionResult extract(MultipartFile file, String lowerName) {
        try {
            if (isImage(lowerName)) {
                return extractImage(file.getBytes());
            }
            if (lowerName.endsWith(".pdf")) {
                return extractPdf(file.getBytes());
            }
            if (lowerName.endsWith(".docx")) {
                return extractDocx(file.getBytes());
            }
            String text = normalizeText(StreamUtils.copyToString(file.getInputStream(), StandardCharsets.UTF_8));
            return requireText(text, false);
        } catch (PolicyDocumentExtractionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PolicyDocumentExtractionException(400, "无法读取制度文件，请确认文件未损坏", exception);
        }
    }

    public PolicyOcrStatusVo ocrStatus() {
        return ocrEngine.status();
    }

    private ExtractionResult extractImage(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new PolicyDocumentExtractionException(400, "无法读取图片，请上传 PNG、JPG 或 BMP 文件");
        }
        try {
            assertImageSize(image);
            return requireText(ocrEngine.recognize(image, "图片"), true);
        } finally {
            image.flush();
        }
    }

    private ExtractionResult extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = PDDocument.load(bytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new PolicyDocumentExtractionException(400, "PDF 中没有可读取的页面");
            }
            if (pageCount > maxPdfPages) {
                throw new PolicyDocumentExtractionException(400,
                        "扫描 PDF 最多支持 " + maxPdfPages + " 页，请拆分后上传");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            PDFRenderer renderer = null;
            List<String> pages = new ArrayList<>();
            boolean usedOcr = false;
            long startedAt = System.currentTimeMillis();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                String embeddedText = normalizeText(stripper.getText(document));
                PDPage page = document.getPage(pageIndex);
                boolean likelyScanned = meaningfulCharacters(embeddedText) < 15
                        && containsLargeRasterImage(page.getResources(), 0);
                if (!likelyScanned) {
                    if (!embeddedText.isEmpty()) {
                        pages.add(embeddedText);
                    }
                    continue;
                }

                if (renderer == null) {
                    renderer = new PDFRenderer(document);
                }
                if (documentTimeoutMs > 0 && System.currentTimeMillis() - startedAt >= documentTimeoutMs) {
                    throw new PolicyDocumentExtractionException(503,
                            "OCR 文档处理超时，请拆分 PDF 后重试");
                }
                float effectiveDpi = effectiveDpi(page.getCropBox());
                BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, effectiveDpi, ImageType.GRAY);
                try {
                    assertImageSize(pageImage);
                    String recognized = ocrEngine.recognize(pageImage, "PDF 第 " + (pageIndex + 1) + " 页");
                    if (!recognized.isEmpty()) {
                        pages.add(recognized);
                    }
                    usedOcr = true;
                } finally {
                    pageImage.flush();
                }
            }
            return requireText(String.join("\n\n", pages), usedOcr);
        }
    }

    private ExtractionResult extractDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = document.getParagraphs().stream()
                    .map(item -> item.getText())
                    .filter(item -> item != null && !item.trim().isEmpty())
                    .collect(Collectors.joining("\n\n"));
            if (normalizeText(text).isEmpty() && !document.getAllPictures().isEmpty()) {
                throw new PolicyDocumentExtractionException(400,
                        "当前 Word 只包含图片；请将扫描页导出为 PDF 或 PNG/JPG 后使用 OCR 上传");
            }
            return requireText(text, false);
        }
    }

    private ExtractionResult requireText(String text, boolean usedOcr) {
        String normalized = normalizeText(text);
        if (meaningfulCharacters(normalized) < 4) {
            if (usedOcr) {
                throw new PolicyDocumentExtractionException(400,
                        "OCR 未识别到有效文字，请上传更清晰、方向正确的扫描件");
            }
            throw new PolicyDocumentExtractionException(400, "文件中未提取到有效的制度正文");
        }
        return new ExtractionResult(normalized, usedOcr);
    }

    private void assertImageSize(BufferedImage image) {
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > maxImagePixels) {
            throw new PolicyDocumentExtractionException(400,
                    "图片分辨率过大，最多支持 " + maxImagePixels + " 像素");
        }
    }

    private float effectiveDpi(PDRectangle box) {
        float configured = Math.max(72F, pdfDpi);
        if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
            return configured;
        }
        double areaInches = (box.getWidth() / 72D) * (box.getHeight() / 72D);
        if (areaInches <= 0) {
            return configured;
        }
        float limited = (float) Math.sqrt(maxImagePixels / areaInches);
        if (limited < 72F) {
            throw new PolicyDocumentExtractionException(400, "PDF 页面尺寸过大，请缩小后重试");
        }
        return Math.min(configured, limited);
    }

    private boolean containsLargeRasterImage(PDResources resources, int depth) throws IOException {
        if (resources == null || depth > 3) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject) {
                PDImageXObject image = (PDImageXObject) object;
                if ((long) image.getWidth() * image.getHeight() >= LARGE_PDF_IMAGE_PIXELS) {
                    return true;
                }
            } else if (object instanceof PDFormXObject
                    && containsLargeRasterImage(((PDFormXObject) object).getResources(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImage(String lowerName) {
        String name = lowerName == null ? "" : lowerName.toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".bmp");
    }

    private static int meaningfulCharacters(String value) {
        return value == null ? 0 : value.replaceAll("[\\s\\p{Punct}，。；：！？、“”‘’【】（）《》]+", "").length();
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

    public static final class ExtractionResult {
        private final String text;
        private final boolean ocrUsed;

        public ExtractionResult(String text, boolean ocrUsed) {
            this.text = text;
            this.ocrUsed = ocrUsed;
        }

        public String getText() {
            return text;
        }

        public boolean isOcrUsed() {
            return ocrUsed;
        }
    }
}
