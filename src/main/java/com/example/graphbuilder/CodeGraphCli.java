package com.example.graphbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CodeGraphCli {

    private static final Logger LOG = LoggerFactory.getLogger(CodeGraphCli.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String SRC_MAIN_JAVA = "src/main/java";
    private static final String TARGET_DIR = "target";
    private static final String GIT_DIR = ".git";
    private static final String OUTPUT_DIR = ".code-graph";
    private static final String CLASSES_DIR = "classes";

    public void generateGraphDependencies(String[] args) throws Exception {
        Path projectRoot = resolveProjectRoot(args);
        Path outDir = projectRoot.resolve(OUTPUT_DIR);
        Files.createDirectories(outDir.resolve(CLASSES_DIR));

        LOG.info("🚀 [1/5] Сканирование структуры проекта: {}", projectRoot);
        Map<String, Object> meta = scanProjectStructure(projectRoot);
        Files.writeString(outDir.resolve("meta.json"), MAPPER.writeValueAsString(meta));

        LOG.info("🔍 [2/5] Построение AST через Spoon...");
        CtModel model = buildModel(projectRoot);

        LOG.info("⚡ [3/5] Извлечение графа, связей и эндпоинтов...");
        ExtractionResult result = extractGraph(model, projectRoot, outDir);

        LOG.info("💾 [4/5] Запись шардов и плоских файлов...");
        writeOutput(outDir, result);

        LOG.info("✅ [5/5] Анализ успешно завершен! Файлы сохранены в: {}", outDir);
    }

    // ========== Stage 1: Project root resolution ==========

    private static Path resolveProjectRoot(String[] args) {
        return args.length > 0 ? Path.of(args[0]).toAbsolutePath() : Path.of(".").toAbsolutePath();
    }

    // ========== Stage 2: Spoon model building ==========

    private static CtModel buildModel(Path projectRoot) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.getEnvironment().setIgnoreSyntaxErrors(true);

        try (Stream<Path> walk = Files.walk(projectRoot)) {
            walk.filter(p -> p.toString().endsWith(SRC_MAIN_JAVA))
                    .filter(path -> !path.toString().contains("/" + TARGET_DIR + "/")
                            && !path.toString().contains("\\" + TARGET_DIR + "\\"))
                    .filter(path -> !path.toString().contains("/" + GIT_DIR + "/")
                            && !path.toString().contains("\\" + GIT_DIR + "\\"))
                    .map(p -> p.toAbsolutePath().toString())
                    .distinct()
                    .forEach(launcher::addInputResource);
        } catch (IOException e) {
            LOG.warn("Ошибка при обходе дерева исходников: {}", e.getMessage());
        }

        return launcher.buildModel();
    }

    // ========== Stage 3: Graph extraction ==========

    private static ExtractionResult extractGraph(CtModel model, Path projectRoot, Path outDir) throws IOException {
        List<String> graphEdges = new ArrayList<>();
        List<String> calledByEdges = new ArrayList<>();
        List<String> endpointsJsonl = new ArrayList<>();
        Set<String> seenFqns = new HashSet<>();

        for (CtType<?> type : model.getAllTypes()) {
            if (type.isAnonymous() || type.getPackage() == null) continue;

            String className = type.getQualifiedName();
            if (seenFqns.contains(className)) {
                String dupFile = getRelativePath(projectRoot, type);
                LOG.warn("Пропускаю дубль: {} ({})", className, dupFile);
                continue;
            }
            seenFqns.add(className);
            String relativeFile = getRelativePath(projectRoot, type);
            int classLine = type.getPosition().isValidPosition() ? type.getPosition().getLine() : 1;

            extractInheritance(type, className, relativeFile, classLine, graphEdges);
            extractClassStereotypes(type, className, relativeFile, classLine, graphEdges);
            extractAutowiredFields(type, className, relativeFile, classLine, graphEdges);

            Map<String, Object> classDumpMethods = new LinkedHashMap<>();
            String baseMapping = extractMappingPath(type.getAnnotations());

            for (CtMethod<?> method : type.getMethods()) {
                String methodSig = formatMethodSignature(className, method);
                int mLine = method.getPosition().isValidPosition() ? method.getPosition().getLine() : classLine;

                extractEndpoint(method, className, methodSig, relativeFile, mLine, baseMapping, endpointsJsonl);
                extractMethodAnnotations(method, methodSig, relativeFile, mLine, graphEdges);
                extractMethodCalls(method, methodSig, relativeFile, mLine, graphEdges, calledByEdges);

                classDumpMethods.put(method.getSimpleName(), Map.of(
                        "signature", methodSig,
                        "line", mLine,
                        "visibility", method.getVisibility() != null ? method.getVisibility().toString() : "package-private",
                        "annotations", method.getAnnotations().stream()
                                .map(a -> a.getAnnotationType().getSimpleName()).toList()
                ));
            }

            writeClassDump(outDir, className, relativeFile, classLine, type, classDumpMethods);
        }

        return new ExtractionResult(graphEdges, calledByEdges, endpointsJsonl);
    }

    private static void extractInheritance(CtType<?> type, String className, String file, int line, List<String> edges) {
        if (type.getSuperclass() != null) {
            edges.add(tsv(className, "extends", type.getSuperclass().getQualifiedName(), file, line, "inheritance"));
        }
        for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
            edges.add(tsv(className, "implements", iface.getQualifiedName(), file, line, "interface"));
        }
    }

    private static void extractAutowiredFields(CtType<?> type, String className, String file, int line, List<String> edges) {
        for (CtField<?> field : type.getFields()) {
            if (hasAnyAnnotation(field, "Autowired", "Inject", "Resource")) {
                String fieldType = field.getType().getQualifiedName();
                int fLine = field.getPosition().isValidPosition() ? field.getPosition().getLine() : line;
                edges.add(tsv(className, "autowire_field", fieldType, file, fLine, "field: " + field.getSimpleName()));
            }
        }
    }

    private static void extractMethodCalls(CtMethod<?> method, String methodSig, String file, int line,
                                           List<String> graphEdges, List<String> calledByEdges) {
        method.getElements(e -> e instanceof CtAbstractInvocation).forEach(inv -> {
            CtAbstractInvocation<?> invocation = (CtAbstractInvocation<?>) inv;
            int callLine = invocation.getPosition().isValidPosition() ? invocation.getPosition().getLine() : line;

            String target;
            String typeCall;
            if (invocation instanceof CtConstructorCall<?> constr) {
                target = constr.getType().getQualifiedName() + "#<init>";
                typeCall = "instantiate";
            } else {
                target = resolveInvocationTarget(invocation);
                typeCall = "call";
            }

            graphEdges.add(tsv(methodSig, typeCall, target, file, callLine, "invokes"));
            calledByEdges.add(target + "\t" + methodSig + "\t" + file + ":" + callLine);
        });
    }

    private static void writeClassDump(Path outDir, String className, String relativeFile, int classLine,
                                       CtType<?> type, Map<String, Object> methods) throws IOException {
        Map<String, Object> classDump = Map.of(
                "class", className,
                "file", relativeFile,
                "line", classLine,
                "kind", type.getClass().getSimpleName().replace("Ct", "").replace("Impl", "").toLowerCase(),
                "annotations", type.getAnnotations().stream()
                        .map(a -> a.getAnnotationType().getSimpleName()).toList(),
                "methods", methods
        );
        Files.writeString(outDir.resolve(CLASSES_DIR + "/" + className + ".json"),
                MAPPER.writeValueAsString(classDump));
    }

    // ========== Stage 4: Output writing ==========

    private static void writeOutput(Path outDir, ExtractionResult result) throws IOException {
        Files.write(outDir.resolve("graph.tsv"), result.graphEdges(), StandardCharsets.UTF_8);
        Files.write(outDir.resolve("called_by.tsv"), result.calledByEdges(), StandardCharsets.UTF_8);
        Files.write(outDir.resolve("endpoints.jsonl"), result.endpointsJsonl(), StandardCharsets.UTF_8);
    }

    // ========== Helper methods ==========

    static String tsv(String from, String type, String to, String file, int line, String note) {
        return String.join("\t", from, type, to, file + ":" + line, note);
    }

    private static String formatMethodSignature(String className, CtMethod<?> method) {
        String params = method.getParameters().stream()
                .map(p -> p.getType().getSimpleName())
                .collect(Collectors.joining(","));
        return className + "#" + method.getSimpleName() + "(" + params + ")";
    }

    private static String resolveInvocationTarget(CtAbstractInvocation<?> inv) {
        if (inv.getExecutable() != null && inv.getExecutable().getDeclaringType() != null) {
            String owner = inv.getExecutable().getDeclaringType().getQualifiedName();
            String name = inv.getExecutable().getSimpleName();
            String params = inv.getExecutable().getParameters().stream()
                    .map(CtTypeReference::getSimpleName)
                    .collect(Collectors.joining(","));
            return owner + "#" + name + "(" + params + ")";
        }
        return "unresolved#" + inv.getShortRepresentation();
    }

    private static void extractClassStereotypes(CtType<?> type, String className, String file, int line, List<String> edges) {
        for (CtAnnotation<?> ann : type.getAnnotations()) {
            String name = safeAnnotationName(ann);
            if (name != null && name.matches("RestController|Controller|Service|Repository|Component|Configuration")) {
                edges.add(tsv(className, "stereotype", "@" + name, file, line, "spring_component"));
            }
        }
    }

    private static void extractMethodAnnotations(CtMethod<?> method, String sig, String file, int line, List<String> edges) {
        for (CtAnnotation<?> ann : method.getAnnotations()) {
            String name = safeAnnotationName(ann);
            if (name == null) continue;
            if (name.equals("Transactional")) {
                edges.add(tsv(sig, "has_transaction", "@Transactional", file, line, "transaction_boundary"));
            } else if (name.equals("EventListener")) {
                edges.add(tsv(sig, "listens_to_event", "ApplicationEvent", file, line, "event_listener"));
            } else if (name.equals("Bean")) {
                edges.add(tsv(sig, "declares_bean", method.getType().getQualifiedName(), file, line, "bean_factory"));
            }
        }
    }

    private static void extractEndpoint(CtMethod<?> method, String className, String sig, String file, int line,
                                        String baseMapping, List<String> endpoints) {
        for (CtAnnotation<?> ann : method.getAnnotations()) {
            String annName = safeAnnotationName(ann);
            if (annName == null) continue;

            String httpMethod = switch (annName) {
                case "GetMapping" -> "GET";
                case "PostMapping" -> "POST";
                case "PutMapping" -> "PUT";
                case "DeleteMapping" -> "DELETE";
                case "PatchMapping" -> "PATCH";
                case "RequestMapping" -> "REQUEST";
                default -> null;
            };

            if (httpMethod != null) {
                String methodPath;
                try {
                    methodPath = extractAnnotationValue(ann);
                } catch (Exception ignored) {
                    methodPath = "";
                }
                String fullPath = normalizePath(baseMapping + "/" + methodPath);

                Map<String, Object> ep = new LinkedHashMap<>();
                ep.put("method", httpMethod);
                ep.put("path", fullPath);
                ep.put("handler", sig);
                ep.put("location", file + ":" + line);
                try {
                    endpoints.add(MAPPER.writeValueAsString(ep));
                } catch (Exception ignored) {}
            }
        }
    }

    private static String safeAnnotationName(CtAnnotation<?> ann) {
        try {
            return ann.getAnnotationType().getSimpleName();
        } catch (Exception e) {
            LOG.debug("Не удалось получить имя аннотации: {}", e.getMessage());
            return null;
        }
    }

    private static String extractMappingPath(Collection<CtAnnotation<?>> annotations) {
        for (CtAnnotation<?> ann : annotations) {
            String name = safeAnnotationName(ann);
            if (name != null && name.contains("Mapping")) {
                try {
                    return extractAnnotationValue(ann);
                } catch (Exception ignored) {
                    continue;
                }
            }
        }
        return "";
    }

    private static String extractAnnotationValue(CtAnnotation<?> ann) {
        try {
            Map<String, CtExpression> values = ann.getValues();
            for (String key : new String[]{"value", "path"}) {
                if (values.get(key) != null) {
                    return annotationValueToString(values.get(key));
                }
            }
        } catch (Exception e) {
            LOG.debug("Не удалось извлечь значение аннотации: {}", e.getMessage());
        }
        return "";
    }

    private static String annotationValueToString(CtElement expr) {
        if (expr instanceof CtLiteral literal) {
            Object value = literal.getValue();
            return value == null ? "" : String.valueOf(value).trim();
        }
        if (expr instanceof CtNewArray array) {
            StringBuilder sb = new StringBuilder();
            for (Object element : array.getElements()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                if (element instanceof CtElement) {
                    sb.append(annotationValueToString((CtElement) element));
                }
            }
            return sb.toString();
        }
        return String.valueOf(expr).trim();
    }

    static String normalizePath(String path) {
        return path.replaceAll("//+", "/").replaceAll("/$", "");
    }

    private static boolean hasAnyAnnotation(CtElement elem, String... names) {
        Set<String> set = Set.of(names);
        return elem.getAnnotations().stream()
                .map(a -> safeAnnotationName(a))
                .filter(Objects::nonNull)
                .anyMatch(set::contains);
    }

    private static String getRelativePath(Path root, CtType<?> type) {
        if (type.getPosition().isValidPosition()) {
            File f = type.getPosition().getFile();
            if (f != null) return root.relativize(f.toPath()).toString().replace('\\', '/');
        }
        return "unknown";
    }

    private static Map<String, Object> scanProjectStructure(Path root) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("build_system", Files.exists(root.resolve("pom.xml")) ? "Maven" : "Gradle");

        try {
            if (Files.exists(root.resolve("pom.xml"))) {
                Matcher m = Pattern.compile("<spring-boot\\.version>(.*?)</spring-boot\\.version>")
                        .matcher(Files.readString(root.resolve("pom.xml")));
                if (m.find()) meta.put("spring_boot_version", m.group(1));
            }
        } catch (Exception e) {
            LOG.debug("Не удалось прочитать версию Spring Boot из pom.xml: {}", e.getMessage());
        }

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (content.contains("@SpringBootApplication")) {
                                Matcher m = Pattern.compile("package\\s+([a-zA-Z0-9_.]+);").matcher(content);
                                if (m.find()) meta.put("base_package", m.group(1));
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            LOG.debug("Не удалось найти базовый пакет: {}", e.getMessage());
        }

        return meta;
    }

    // ========== Result container ==========

    private record ExtractionResult(
            List<String> graphEdges,
            List<String> calledByEdges,
            List<String> endpointsJsonl
    ) {}
}