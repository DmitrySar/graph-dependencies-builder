package com.example.graphbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CodeGraphCli {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void generateGraphDependencies(String[] args) throws Exception {
        Path projectRoot = args.length > 0 ? Path.of(args[0]).toAbsolutePath() : Path.of(".").toAbsolutePath();
        Path outDir = projectRoot.resolve(".code-graph");
        Files.createDirectories(outDir.resolve("classes"));

        System.out.println("🚀 [1/5] Сканирование структуры проекта: " + projectRoot);
        Map<String, Object> meta = scanProjectStructure(projectRoot);
        Files.writeString(outDir.resolve("meta.json"), MAPPER.writeValueAsString(meta));

        System.out.println("🔍 [2/5] Построение AST через Spoon...");
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true); // Парсит даже если зависимости не скачаны
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setCommentEnabled(false);

        // Добавляем все директории с исходниками проекта
        Files.walk(projectRoot)
                .filter(p -> p.toString().endsWith(Paths.get("src", "main", "java").toString()))
                .forEach(p -> launcher.addInputResource(p.toString()));

        CtModel model = launcher.buildModel();

        System.out.println("⚡ [3/5] Извлечение графа, связей и эндпоинтов...");
        List<String> graphEdges = new ArrayList<>();
        List<String> calledByEdges = new ArrayList<>();
        List<String> endpointsJsonl = new ArrayList<>();

        for (CtType<?> type : model.getAllTypes()) {
            if (type.isAnonymous() || type.getPackage() == null) continue;

            String className = type.getQualifiedName();
            String relativeFile = getRelativePath(projectRoot, type);
            int classLine = type.getPosition().isValidPosition() ? type.getPosition().getLine() : 1;

            // Наследование и интерфейсы
            if (type.getSuperclass() != null) {
                graphEdges.add(tsv(className, "extends", type.getSuperclass().getQualifiedName(), relativeFile, classLine, "inheritance"));
            }
            for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
                graphEdges.add(tsv(className, "implements", iface.getQualifiedName(), relativeFile, classLine, "interface"));
            }

            // Spring стереотипы
            extractClassStereotypes(type, className, relativeFile, classLine, graphEdges);

            // Обработка полей (DI: @Autowired, @Inject)
            for (CtField<?> field : type.getFields()) {
                if (hasAnyAnnotation(field, "Autowired", "Inject", "Resource")) {
                    String fieldType = field.getType().getQualifiedName();
                    int fLine = field.getPosition().isValidPosition() ? field.getPosition().getLine() : classLine;
                    graphEdges.add(tsv(className, "autowire_field", fieldType, relativeFile, fLine, "field: " + field.getSimpleName()));
                }
            }

            // Обработка методов
            Map<String, Object> classDumpMethods = new LinkedHashMap<>();
            String baseMapping = extractMappingPath(type.getAnnotations());

            for (CtMethod<?> method : type.getMethods()) {
                String methodSig = formatMethodSignature(className, method);
                int mLine = method.getPosition().isValidPosition() ? method.getPosition().getLine() : classLine;

                // Проверка REST-эндпоинтов
                extractEndpoint(method, className, methodSig, relativeFile, mLine, baseMapping, endpointsJsonl);

                // Spring-аннотации метода (@Transactional, @EventListener, @Bean)
                extractMethodAnnotations(method, methodSig, relativeFile, mLine, graphEdges);

                // Поиск вызовов внутри метода (calls)
                method.getElements(e -> e instanceof CtAbstractInvocation).forEach(inv -> {
                    CtAbstractInvocation<?> invocation = (CtAbstractInvocation<?>) inv;
                    int callLine = invocation.getPosition().isValidPosition() ? invocation.getPosition().getLine() : mLine;

                    String target;
                    String typeCall;
                    if (invocation instanceof CtConstructorCall<?> constr) {
                        target = constr.getType().getQualifiedName() + "#<init>";
                        typeCall = "instantiate";
                    } else {
                        target = resolveInvocationTarget(invocation);
                        typeCall = "call";
                    }

                    graphEdges.add(tsv(methodSig, typeCall, target, relativeFile, callLine, "invokes"));
                    calledByEdges.add(target + "\t" + methodSig + "\t" + relativeFile + ":" + callLine);
                });

                classDumpMethods.put(method.getSimpleName(), Map.of(
                        "signature", methodSig,
                        "line", mLine,
                        "visibility", method.getVisibility() != null ? method.getVisibility().toString() : "package-private",
                        "annotations", method.getAnnotations().stream().map(a -> a.getAnnotationType().getSimpleName()).toList()
                ));
            }

            // Сохраняем индивидуальный файл класса
            Map<String, Object> classDump = Map.of(
                    "class", className,
                    "file", relativeFile,
                    "line", classLine,
                    "kind", type.getClass().getSimpleName().replace("Ct", "").replace("Impl", "").toLowerCase(),
                    "annotations", type.getAnnotations().stream().map(a -> a.getAnnotationType().getSimpleName()).toList(),
                    "methods", classDumpMethods
            );
            Files.writeString(outDir.resolve("classes/" + className + ".json"), MAPPER.writeValueAsString(classDump));
        }

        System.out.println("💾 [4/5] Запись шардов и плоских файлов...");
        Files.write(outDir.resolve("graph.tsv"), graphEdges, StandardCharsets.UTF_8);
        Files.write(outDir.resolve("called_by.tsv"), calledByEdges, StandardCharsets.UTF_8);
        Files.write(outDir.resolve("endpoints.jsonl"), endpointsJsonl, StandardCharsets.UTF_8);

        System.out.println("✅ [5/5] Анализ успешно завершен! Файлы сохранены в: " + outDir);
    }

    // Вспомогательные методы извлечения семантики

    private static String tsv(String from, String type, String to, String file, int line, String note) {
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
            String name = ann.getAnnotationType().getSimpleName();
            if (name.matches("RestController|Controller|Service|Repository|Component|Configuration")) {
                edges.add(tsv(className, "stereotype", "@" + name, file, line, "spring_component"));
            }
        }
    }

    private static void extractMethodAnnotations(CtMethod<?> method, String sig, String file, int line, List<String> edges) {
        for (CtAnnotation<?> ann : method.getAnnotations()) {
            String name = ann.getAnnotationType().getSimpleName();
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
            String annName = ann.getAnnotationType().getSimpleName();
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
                String methodPath = extractAnnotationValue(ann);
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

    private static String extractMappingPath(Collection<CtAnnotation<?>> annotations) {
        for (CtAnnotation<?> ann : annotations) {
            if (ann.getAnnotationType().getSimpleName().contains("Mapping")) {
                return extractAnnotationValue(ann);
            }
        }
        return "";
    }

    private static String extractAnnotationValue(CtAnnotation<?> ann) {
        if (ann.getValue("value") != null) {
            return ann.getValue("value").toString().replaceAll("[\"\\[\\]]", "");
        }
        if (ann.getValue("path") != null) {
            return ann.getValue("path").toString().replaceAll("[\"\\[\\]]", "");
        }
        return "";
    }

    private static String normalizePath(String path) {
        return path.replaceAll("//+", "/").replaceAll("/$", "");
    }

    private static boolean hasAnyAnnotation(CtElement elem, String... names) {
        Set<String> set = Set.of(names);
        return elem.getAnnotations().stream().anyMatch(a -> set.contains(a.getAnnotationType().getSimpleName()));
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

        // Поиск базового пакета и Spring Boot версии
        try {
            if (Files.exists(root.resolve("pom.xml"))) {
                String pom = Files.writeString(Path.of("/tmp/pom"), Files.readString(root.resolve("pom.xml"))).toString();
                Matcher m = Pattern.compile("<spring-boot\\.version>(.*?)</spring-boot\\.version>").matcher(Files.readString(root.resolve("pom.xml")));
                if (m.find()) meta.put("spring_boot_version", m.group(1));
            }
            Files.walk(root)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (content.contains("@SpringBootApplication")) {
                                Matcher m = Pattern.compile("package\\s+([a-zA-Z0-9_.]+);").matcher(content);
                                if (m.find()) meta.put("base_package", m.group(1));
                            }
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
        return meta;
    }
}
