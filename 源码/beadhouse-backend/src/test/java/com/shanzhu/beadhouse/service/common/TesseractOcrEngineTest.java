package com.shanzhu.beadhouse.service.common;

import com.shanzhu.beadhouse.entity.vo.PolicyOcrStatusVo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class TesseractOcrEngineTest {
    @Test
    void installedTesseractCanRecognizeAnEnglishImageWhenAvailable() {
        String executable = "C:\\Program Files\\Tesseract-OCR\\tesseract.exe";
        Assumptions.assumeTrue(Files.isRegularFile(Paths.get(executable)), "local Tesseract is optional");

        TesseractOcrEngine engine = new TesseractOcrEngine();
        ReflectionTestUtils.setField(engine, "enabled", true);
        ReflectionTestUtils.setField(engine, "configuredCommand", executable);
        ReflectionTestUtils.setField(engine, "language", "eng");
        ReflectionTestUtils.setField(engine, "dataDir", "");
        ReflectionTestUtils.setField(engine, "timeoutMs", 15000L);
        ReflectionTestUtils.setField(engine, "statusCacheMs", 0L);

        PolicyOcrStatusVo status = engine.status();
        Assumptions.assumeTrue(status.isAvailable(), status.getMessage());

        BufferedImage image = new BufferedImage(1200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Arial", Font.BOLD, 64));
        graphics.drawString("CARE POLICY 2026", 60, 180);
        graphics.dispose();
        try {
            assertThat(engine.recognize(image, "test")).containsIgnoringCase("CARE");
        } finally {
            image.flush();
        }
    }

    @Test
    void configuredChineseLanguageDataCanRecognizeChineseWhenAvailable() {
        String executable = "C:\\Program Files\\Tesseract-OCR\\tesseract.exe";
        String dataDir = "..\\..\\.runtime\\ocr\\tessdata";
        Assumptions.assumeTrue(Files.isRegularFile(Paths.get(executable)), "local Tesseract is optional");
        Assumptions.assumeTrue(Files.isRegularFile(Paths.get(dataDir, "chi_sim.traineddata")),
                "Chinese Tesseract language data is optional");

        TesseractOcrEngine engine = new TesseractOcrEngine();
        ReflectionTestUtils.setField(engine, "enabled", true);
        ReflectionTestUtils.setField(engine, "configuredCommand", executable);
        ReflectionTestUtils.setField(engine, "language", "chi_sim+eng");
        ReflectionTestUtils.setField(engine, "dataDir", dataDir);
        ReflectionTestUtils.setField(engine, "timeoutMs", 15000L);
        ReflectionTestUtils.setField(engine, "statusCacheMs", 0L);

        PolicyOcrStatusVo status = engine.status();
        Assumptions.assumeTrue(status.isAvailable(), status.getMessage());

        BufferedImage image = new BufferedImage(1400, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Microsoft YaHei", Font.BOLD, 72));
        graphics.drawString("护理制度 跌倒处理", 60, 220);
        graphics.dispose();
        try {
            assertThat(engine.recognize(image, "test")).contains("护理");
        } finally {
            image.flush();
        }
    }
}
