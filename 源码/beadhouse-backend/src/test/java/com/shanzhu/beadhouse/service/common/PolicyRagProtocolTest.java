package com.shanzhu.beadhouse.service.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyRagProtocolTest {
    @Test void canonicalHashMatchesSharedPythonFixtureWithoutLosingLongOrUnicode() throws Exception {
        Path root=Paths.get("").toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve(".github/rag/sync_protocol_fixture.json"))) root=root.getParent();
        assertThat(root).as("repository protocol fixture must exist").isNotNull();
        JsonNode fixture=new ObjectMapper().readTree(root.resolve(".github/rag/sync_protocol_fixture.json").toFile());
        assertThat(fixture.path("requests").size()).isEqualTo(2);
        for(JsonNode request:fixture.path("requests")) {
            assertThat(PolicyRagPayload.canonicalPayloadHash(request)).isEqualTo(request.path("payload_hash").asText());
        }
        assertThat(fixture.path("requests").get(0).path("chunks").get(0).path("chunk_id").asLong())
                .isEqualTo(9007199254740993L);
    }
}
