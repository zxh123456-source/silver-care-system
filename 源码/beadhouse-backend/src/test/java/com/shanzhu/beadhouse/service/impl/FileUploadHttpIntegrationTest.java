package com.shanzhu.beadhouse.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SCOPE_HTTP_REDIS_PORT", matches="[0-9]+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.druid.url=${SCOPE_TEST_DB_URL}", "spring.datasource.druid.password=${SCOPE_TEST_DB_PASSWORD}",
    "spring.redis.port=${SCOPE_HTTP_REDIS_PORT}", "spring.redis.password=scope-redis-only",
    "ai.rag.enabled=false", "ai.provider.enabled=false", "security.password-reset.email-enabled=false",
    "filesave.windows=./target/upload-http", "filesave.linux=./target/upload-http", "filesave.macos=./target/upload-http",
    "filesave.upload-head=", "spring.quartz.auto-startup=false"})
class FileUploadHttpIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    private String token;

    @BeforeEach void login() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        http.getRestTemplate().setRequestFactory(factory);
        String password = "upload-http-test-only";
        jdbc.update("UPDATE staff SET pass=? WHERE id=1", new BCryptPasswordEncoder().encode(password));
        Map<String,String> credentials = new HashMap<>();
        credentials.put("phone", jdbc.queryForObject("SELECT phone FROM staff WHERE id=1", String.class));
        credentials.put("pass", password);
        JsonNode login = http.postForObject("/account/login", credentials, JsonNode.class);
        assertThat(login.path("code").asInt()).isEqualTo(200);
        token = login.path("data").path("token").asText();
    }

    @Test void rejectsUnsafeMultipartUploadsWithoutCreatingAttachmentRecords() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM base_attachment", Long.class);
        assertThat(upload("uploadImg", "photo.png", png(), null).getStatusCodeValue()).isEqualTo(401);
        assertRejected("../escaped.png", png());
        assertRejected("fake.png", "<html>not a picture</html>".getBytes("UTF-8"));
        assertRejected("picture.svg", "<svg/>".getBytes("UTF-8"));
        assertRejected("empty.png", new byte[0]);
        assertRejected("large.png", new byte[2 * 1024 * 1024 + 1]);
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA); headers.set("token", token);
        MultiValueMap<String,Object> noFile = new LinkedMultiValueMap<>(); noFile.add("other", "missing file");
        JsonNode missing = http.postForObject("/file/uploadImg", new HttpEntity<>(noFile, headers), JsonNode.class);
        assertThat(missing.path("code").asInt()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM base_attachment", Long.class)).isEqualTo(before);
    }

    @Test void uploadedImagesRemainViewableAndAttachmentsDownloadWithSafeHeaders() throws Exception {
        byte[] image = png(), attachment = "<!doctype html><title>test fixture</title>".getBytes("UTF-8");
        verifyStoredResource("uploadImg", "photo.PNG", image, false);
        verifyStoredResource("uploadFile", "document.html", attachment, true);
    }

    private void verifyStoredResource(String endpoint, String filename, byte[] content, boolean attachment) throws Exception {
        JsonNode result = upload(endpoint, filename, content, token).getBody();
        assertThat(result.path("code").asInt()).isEqualTo(200);
        long id = result.path("data").path("id").asLong();
        String url = result.path("data").path("url").asText();
        Path path = Paths.get(jdbc.queryForObject("SELECT path FROM base_attachment WHERE id=?", String.class, id));
        try {
            assertThat(url).matches("/upload/(img|file)/[0-9]{8}/[a-f0-9]{32}\\.(png|html)");
            ResponseEntity<byte[]> download = http.getForEntity(url, byte[].class);
            assertThat(download.getStatusCodeValue()).isEqualTo(200);
            assertThat(download.getBody()).isEqualTo(content);
            assertThat(download.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(download.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
            if (attachment) assertThat(download.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment");
            else assertThat(download.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(jdbc.queryForObject("SELECT real_name FROM base_attachment WHERE id=?", String.class, id)).isEqualTo(filename);
        } finally {
            assertThat(path.toAbsolutePath().normalize()).startsWith(Paths.get("target/upload-http").toAbsolutePath().normalize());
            Files.deleteIfExists(path);
            jdbc.update("DELETE FROM base_attachment WHERE id=?", id);
        }
    }

    private void assertRejected(String filename, byte[] data) {
        JsonNode result = upload("uploadImg", filename, data, token).getBody();
        assertThat(result.path("code").asInt()).as(filename).isEqualTo(400);
        assertThat(result.path("data").isNull() || result.path("data").isMissingNode()).isTrue();
    }

    private ResponseEntity<JsonNode> upload(String endpoint, String filename, byte[] data, String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (session != null) headers.set("token", session);
        MultiValueMap<String,Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(data) { @Override public String getFilename() { return filename; } });
        return http.postForEntity("/file/" + endpoint, new HttpEntity<>(parts, headers), JsonNode.class);
    }

    private byte[] png() throws Exception {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", content);
        return content.toByteArray();
    }
}
