package com.example.graphbuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeGraphMcpServerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeGraphMcpServer server = new CodeGraphMcpServer(new CodeGraphCli());

    private JsonNode request(Object id, String method, String paramsJson) throws IOException {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":" + (id == null ? "null" : id.toString())
                + ",\"method\":\"" + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        return server.handle(mapper.readTree(json));
    }

    @Test
    void handle_initializeReturnsProtocolVersionAndTools() throws IOException {
        JsonNode response = request(1, "initialize", "{}");
        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals("2025-06-18", response.path("result").path("protocolVersion").asText());
        assertTrue(response.path("result").path("capabilities").has("tools"));
        assertTrue(response.path("result").path("serverInfo").has("name"));
    }

    @Test
    void handle_notificationReturnsNull() throws IOException {
        assertNull(request(null, "notifications/initialized", null));
    }

    @Test
    void handle_unknownMethodReturnsError() throws IOException {
        JsonNode response = request(2, "bogus/method", null);
        assertEquals(-32603, response.path("error").path("code").asInt());
    }

    @Test
    void handle_toolsListContainsSevenTools() throws IOException {
        JsonNode response = request(3, "tools/list", null);
        JsonNode tools = response.path("result").path("tools");
        assertEquals(7, tools.size());
        assertTrue(tools.toString().contains("analyze_project"));
        assertTrue(tools.toString().contains("get_class"));
    }

    @Test
    void handle_toolsCallGetMetaOnAnalyzedProject(@TempDir Path tempDir) throws Exception {
        copyTestProject(tempDir);
        new CodeGraphCli().generateGraphDependencies(new String[]{tempDir.toString()});

        JsonNode response = request(4, "tools/call",
                "{\"name\":\"get_meta\",\"arguments\":{\"path\":\"" + tempDir + "\"}}");
        assertTrue(response.path("result").path("isError").asBoolean() == false);
        String text = response.path("result").path("content").get(0).path("text").asText();
        assertTrue(text.contains("build_system"));
    }

    @Test
    void handle_toolsCallGetGraphWithEdgeTypeFilter(@TempDir Path tempDir) throws Exception {
        copyTestProject(tempDir);
        new CodeGraphCli().generateGraphDependencies(new String[]{tempDir.toString()});

        JsonNode response = request(5, "tools/call",
                "{\"name\":\"get_graph\",\"arguments\":{\"path\":\"" + tempDir + "\",\"edge_type\":\"stereotype\"}}");
        String text = response.path("result").path("content").get(0).path("text").asText();
        assertFalse(text.isEmpty());
        assertTrue(text.lines().allMatch(l -> l.isBlank()
                || (l.split("\t", -1).length > 1 && (l.split("\t", -1)[1].equals("stereotype") || l.startsWith("FROM\t")))));
    }

    @Test
    void handle_toolsCallBeforeAnalysisReturnsToolError() throws IOException {
        JsonNode response = request(6, "tools/call",
                "{\"name\":\"get_endpoints\",\"arguments\":{\"path\":\"/tmp/definitely-not-analyzed-12345\"}}");
        assertTrue(response.path("result").path("isError").asBoolean());
        assertTrue(response.path("result").path("content").get(0).path("text").asText().contains("analyze_project"));
    }

    @Test
    void handle_toolsCallUnknownToolReturnsToolError() throws IOException {
        JsonNode response = request(7, "tools/call", "{\"name\":\"nope\"}");
        assertTrue(response.path("result").path("isError").asBoolean());
    }

    @Test
    void handle_pingReturnsEmptyResult() throws IOException {
        JsonNode response = request(8, "ping", null);
        assertTrue(response.path("result").isObject());
        assertFalse(response.path("result").has("error"));
    }

    private void copyTestProject(Path target) throws IOException {
        Path source = Path.of("src/test/resources/test-project");
        try (var walk = Files.walk(source)) {
            walk.forEach(p -> {
                try {
                    Path dst = target.resolve(source.relativize(p));
                    Files.createDirectories(dst.getParent());
                    Files.copy(p, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
