package com.shanzhu.beadhouse.service.common;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyDocumentTextExtractorTest {
    @Mock
    private TesseractOcrEngine ocrEngine;

    private PolicyDocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PolicyDocumentTextExtractor();
        ReflectionTestUtils.setField(extractor, "ocrEngine", ocrEngine);
        ReflectionTestUtils.setField(extractor, "pdfDpi", 120F);
        ReflectionTestUtils.setField(extractor, "maxPdfPages", 30);
        ReflectionTestUtils.setField(extractor, "maxImagePixels", 25_000_000L);
    }

    @Test
    void textLayerPdfDoesNotInvokeOcr() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", textPdf());

        PolicyDocumentTextExtractor.ExtractionResult result = extractor.extract(file, "policy.pdf");

        assertThat(result.getText()).contains("CARE POLICY");
        assertThat(result.isOcrUsed()).isFalse();
        verify(ocrEngine, never()).recognize(any(BufferedImage.class), anyString());
    }

    @Test
    void scannedPdfUsesOcrAndKeepsResult() throws Exception {
        when(ocrEngine.recognize(any(BufferedImage.class), anyString()))
                .thenReturn("扫描制度 发现跌倒后立即通知值班人员");
        MockMultipartFile file = new MockMultipartFile("file", "scan.pdf", "application/pdf", imagePdf());

        PolicyDocumentTextExtractor.ExtractionResult result = extractor.extract(file, "scan.pdf");

        assertThat(result.getText()).contains("扫描制度", "跌倒");
        assertThat(result.isOcrUsed()).isTrue();
        verify(ocrEngine).recognize(any(BufferedImage.class), anyString());
    }

    @Test
    void imageUploadUsesOcrAndBlankResultIsRejected() throws Exception {
        when(ocrEngine.recognize(any(BufferedImage.class), anyString())).thenReturn("");
        MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", imagePng());

        assertThatThrownBy(() -> extractor.extract(file, "scan.png"))
                .isInstanceOf(PolicyDocumentExtractionException.class)
                .hasMessageContaining("OCR 未识别到");
        verify(ocrEngine).recognize(any(BufferedImage.class), anyString());
    }

    private static byte[] textPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 18);
                stream.newLineAtOffset(72, 700);
                stream.showText("CARE POLICY: FALL RESPONSE AND REPORTING");
                stream.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] imagePdf() throws Exception {
        BufferedImage image = new BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDImageXObject object = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(object, 0, 0, 612, 792);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static byte[] imagePng() throws Exception {
        BufferedImage image = new BufferedImage(700, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        image.flush();
        return output.toByteArray();
    }
}
