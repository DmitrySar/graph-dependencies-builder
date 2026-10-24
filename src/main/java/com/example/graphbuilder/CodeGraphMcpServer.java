package com.example.graphbuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MCP (Model Context Protocol) сервер поверх stdio.
 * Протокол: newline-delimited JSON-RPC 2.0 (initialize, tools/list, tools/call, ping).
 * Каждый инструмент принимает путь к анализируемому проекту и читает его индекс в .code-graph/.
 */
@Service
public class CodeGraphMcpServer {

    private static final Logger LOG = LoggerFactory.getLogger(CodeGraphMcpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_CONTENT_CHARS = 100_000;

    private final CodeGraphCli codeGraphCli;

    public CodeGraphMcpServer(CodeGraphCli codeGraphCli) {
        this.codeGraphCli = codeGraphCli;
    }

    public void run() throws IOException {
        LOG.info("MCP-сервер запущен, ожидание JSON-RPC сообщений из stdin");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode response;
                try {
                    JsonNode request = MAPPER.readTree(line);
                    response = handle(request);
                } catch (Exception e) {
                    LOG.warn("Не удалось обработать сообщение: {}", e.getMessage());
                    response = error(null, -32700, "Parse error: " + e.getMessage());
                }
                if (response != null) {
                    System.out.println(MAPPER.writeValueAsString(response));
                    System.out.flush();
                }
            }
        }
        LOG.info("stdin закрыт, MCP-сервер завершает работу");
    }

    /**
     * Обрабатывает один JSON-RPC запрос. Возвращает ответ или null для уведомлений.
     */
    public JsonNode handle(JsonNode request) {
        if (!request.has("method")) return null;

        String method = request.path("method").asText();
        boolean isNotification = !request.hasNonNull("id");
        JsonNode id = request.path("id");

        if (isNotification) {
            LOG.debug("Уведомление: {}", method);
            return null;
        }

        try {
            JsonNode result;
            if (method.equals("initialize")) {
                result = initializeResult();
            } else if (method.equals("ping")) {
                result = MAPPER.createObjectNode();
            } else if (method.equals("tools/list")) {
                result = toolsListResult();
            } else if (method.equals("tools/call")) {
                result = toolsCallResult(request.path("params"));
            } else {
                throw new IllegalArgumentException("Unknown method: " + method);
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        } catch (McpToolException e) {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("isError", true);
            result.set("content", textContent(e.getMessage()));
            ObjectNode response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result);
            return response;
        } catch (Exception e) {
            LOG.warn("Ошибка при обработке {}: {}", method, e.getMessage());
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonNode initializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "code-graph-builder");
        serverInfo.put("version", "0.0.1");
        result.put("instructions", "Статический анализ Java-проектов: запускайте analyze_project, затем читайте граф зависимостей инструментами get_graph / get_called_by / get_endpoints / list_classes / get_class.");
        return result;
    }

    private JsonNode toolsListResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(toolDefinition("analyze_project",
                "Проанализировать Java-проект: построить AST (Spoon) и записать индекс зависимостей в <project>/.code-graph/.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Путь к корню анализируемого проекта\"}},\"required\":[\"path\"]}"));
        tools.add(toolDefinition("get_meta",
                "Прочитать meta.json индекса: система сборки, версия Spring Boot, базовый пакет.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Путь к корню проекта\"}},\"required\":[\"path\"]}"));
        tools.add(toolDefinition("get_graph",
                "Прочитать graph.tsv (рёбра графа: FROM, TYPE, TO, FILE:LINE, NOTE) с опциональной фильтрацией.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Путь к корню проекта\"},\"edge_type\":{\"type\":\"string\",\"description\":\"Фильтр по типу ребра: extends, implements, stereotype, autowire_field, call, instantiate, has_transaction, listens_to_event, declares_bean\"},\"contains\":{\"type\":\"string\",\"description\":\"Подстрока, которая должна входить в строку ребра\"}},\"required\":[\"path\"]}"));
        tools.add(toolDefinition("get_called_by",
                "Прочитать called_by.tsv (обратные вызовы: TARGET, CALLER, FILE:LINE) с опциональной фильтрацией по цели.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Путь к корню проекта\"},\"target\":{\"type\":\"string\",\"description\":\"Фильтр: вызываемый метод или класс\"}},\"required\":[\"path\"]}"));
        tools.add(toolDefinition("get_endpoints",
                "Прочитать endpoints.jsonl (REST эндпоинты: method, path, handler, location).",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Путь к корню проекта\"}},\"required\":[\"path\"]}"));
        tools.add(toolDefinition("list_classes",
                "Получить список проанализированных классов (JSON-файлы в .code-graph/classes/).",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Путь к корню проекта\"}},\"required\":[\"path\"]}"));
        tools.add(toolDefinition("get_class",
                "Прочитать дамп одного класса: методы, поля, аннотации, строки.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Путь к корню проекта\"},\"class_name\":{\"type\":\"string\",\"description\":\"Полное имя класса (FQCN)\"}},\"required\":[\"path\",\"class_name\"]}"));
        return result;
    }

    private ObjectNode toolDefinition(String name, String description, String inputSchemaJson) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        try {
            tool.set("inputSchema", MAPPER.readTree(inputSchemaJson));
        } catch (Exception ignored) {}
        return tool;
    }

    private JsonNode toolsCallResult(JsonNode params) {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        // NB: без switch-выражений — Spoon 10.3.0 при ignoreSyntaxErrors(true)
        // ложно отбрасывает файлы с switch-выражениями (Java 14+).
        String resultText;
        if (name.equals("analyze_project")) {
            resultText = analyzeProject(stringArg(args, "path"));
        } else if (name.equals("get_meta")) {
            resultText = readFile(args, "meta.json");
        } else if (name.equals("get_graph") || name.equals("get_called_by")) {
            resultText = readTsvFiltered(args, name.equals("get_graph") ? "graph.tsv" : "called_by.tsv");
        } else if (name.equals("get_endpoints")) {
            resultText = readFile(args, "endpoints.jsonl");
        } else if (name.equals("list_classes")) {
            resultText = listClasses(stringArg(args, "path"));
        } else if (name.equals("get_class")) {
            resultText = getClass(stringArg(args, "path"), stringArg(args, "class_name"));
        } else {
            throw new McpToolException("Неизвестный инструмент: " + name);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("isError", false);
        result.set("content", textContent(resultText));
        return result;
    }

    private static JsonNode textContent(String text) {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "text");
        content.put("text", text);
        return MAPPER.createArrayNode().add(content);
    }

    private String analyzeProject(String path) {
        Path root = Path.of(path).toAbsolutePath();
        try {
            codeGraphCli.generateGraphDependencies(new String[]{root.toString()});
        } catch (Exception e) {
            throw new McpToolException("Анализ не удался: " + e);
        }
        Path outDir = root.resolve(".code-graph");
        return "Анализ завершен. Индекс: " + outDir +
                "\nКлассов: " + countFiles(outDir.resolve("classes")) +
                "\nРёбер графа: " + countLines(outDir.resolve("graph.tsv")) +
                "\nОбратных вызовов: " + countLines(outDir.resolve("called_by.tsv")) +
                "\nЭндпоинтов: " + countLines(outDir.resolve("endpoints.jsonl"));
    }

    private String readFile(JsonNode args, String fileName) {
        Path file = outDir(args).resolve(fileName);
        if (!Files.exists(file)) {
            throw new McpToolException("Файл не найден: " + file + ". Сначала запустите analyze_project.");
        }
        return truncate(readSafe(file));
    }

    private String readTsvFiltered(JsonNode args, String fileName) {
        String full = readFile(args, fileName);
        String edgeType = args.path("edge_type").asText("").trim();
        String contains = args.path("contains").asText("").trim();
        if (contains.isEmpty()) {
            String target = args.path("target").asText("").trim();
            if (!target.isEmpty()) contains = target;
        }
        final String match = contains;
        if (edgeType.isEmpty() && match.isEmpty()) return full;
        String filtered = full.lines()
                .filter(line -> !line.isBlank())
                .filter(line -> edgeType.isEmpty() || (line.split("\t", -1).length > 1 && line.split("\t", -1)[1].equals(edgeType)))
                .filter(line -> match.isEmpty() || line.contains(match))
                .collect(Collectors.joining("\n"));
        return truncate(filtered);
    }

    private String listClasses(String path) {
        Path classesDir = outDir(path).resolve("classes");
        if (!Files.isDirectory(classesDir)) {
            throw new McpToolException("Каталог не найден: " + classesDir + ". Сначала запустите analyze_project.");
        }
        try (Stream<Path> files = Files.list(classesDir)) {
            String names = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return truncate(names.isEmpty() ? "Классы не найдены" : names);
        } catch (IOException e) {
            throw new McpToolException("Не удалось прочитать каталог классов: " + e.getMessage());
        }
    }

    private String getClass(String path, String className) {
        if (className.isBlank()) throw new McpToolException("Не указан class_name");
        Path file = outDir(path).resolve("classes").resolve(className + ".json");
        if (!Files.exists(file)) {
            throw new McpToolException("Класс не найден: " + className + ". Используйте list_classes для списка.");
        }
        return truncate(readSafe(file));
    }

    private static Path outDir(JsonNode args) {
        return outDir(stringArg(args, "path"));
    }

    private static Path outDir(String path) {
        return Path.of(path).toAbsolutePath().resolve(".code-graph");
    }

    private static String stringArg(JsonNode args, String key) {
        return args.path(key).asText("");
    }

    private static String readSafe(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new McpToolException("Не удалось прочитать " + file + ": " + e.getMessage());
        }
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_CONTENT_CHARS) return text;
        return text.substring(0, MAX_CONTENT_CHARS) + "\n… (обрезано, всего символов: " + text.length() + ")";
    }

    private static int countLines(Path file) {
        if (!Files.exists(file)) return 0;
        try (Stream<String> lines = Files.lines(file)) {
            return (int) lines.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static int countFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return (int) files.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.nullNode() : id);
        ObjectNode err = response.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return response;
    }

    private static final class McpToolException extends RuntimeException {
        McpToolException(String message) {
            super(message);
        }
    }
}
