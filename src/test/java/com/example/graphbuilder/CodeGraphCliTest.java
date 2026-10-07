package com.example.graphbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeGraphCliTest {

    private final CodeGraphCli cli = new CodeGraphCli();

    @Test
    void normalizePath_collapsesDoubleSlashes() {
        assertEquals("/api/hello", cli.normalizePath("/api//hello"));
    }

    @Test
    void normalizePath_removesTrailingSlash() {
        assertEquals("/api", cli.normalizePath("/api/"));
    }

    @Test
    void normalizePath_preservesNormalPath() {
        assertEquals("/api/hello", cli.normalizePath("/api/hello"));
    }

    @Test
    void normalizePath_handlesEmptyString() {
        assertEquals("", cli.normalizePath(""));
    }

    @Test
    void tsv_returnsTabSeparatedString() {
        String result = cli.tsv("A", "extends", "B", "File.java", 42, "inheritance");
        assertEquals("A\textends\tB\tFile.java:42\tinheritance", result);
    }

    @Test
    void tsv_includesLineNumber() {
        String result = cli.tsv("X", "calls", "Y", "Foo.java", 10, "invokes");
        assertTrue(result.contains("Foo.java:10"));
    }

    @Test
    void integrationTest_withTestProject(@TempDir Path tempDir) throws Exception {
        // Copy test project to temp dir to avoid modifying original
        Path testProjectSrc = Path.of("src/test/resources/test-project");
        copyDirectory(testProjectSrc, tempDir);

        // Run analysis
        cli.generateGraphDependencies(new String[]{tempDir.toString()});

        // Verify output directory exists
        Path outDir = tempDir.resolve(".code-graph");
        assertTrue(Files.exists(outDir), "Output directory should exist");

        // Verify meta.json
        Path metaFile = outDir.resolve("meta.json");
        assertTrue(Files.exists(metaFile), "meta.json should exist");
        String metaContent = Files.readString(metaFile);
        assertTrue(metaContent.contains("Maven") || metaContent.contains("Gradle"),
                "meta.json should contain build system info");

        // Verify graph.tsv exists and has content
        Path graphFile = outDir.resolve("graph.tsv");
        assertTrue(Files.exists(graphFile), "graph.tsv should exist");
        String graphContent = Files.readString(graphFile);
        assertFalse(graphContent.isEmpty(), "graph.tsv should not be empty");

        // Verify called_by.tsv exists
        Path calledByFile = outDir.resolve("called_by.tsv");
        assertTrue(Files.exists(calledByFile), "called_by.tsv should exist");

        // Verify endpoints.jsonl exists
        Path endpointsFile = outDir.resolve("endpoints.jsonl");
        assertTrue(Files.exists(endpointsFile), "endpoints.jsonl should exist");

        // Verify class JSON files exist for our test classes
        Path testServiceJson = outDir.resolve("classes/com.test.TestService.json");
        assertTrue(Files.exists(testServiceJson), "TestService.json should exist");

        Path testControllerJson = outDir.resolve("classes/com.test.TestController.json");
        assertTrue(Files.exists(testControllerJson), "TestController.json should exist");

        // Verify endpoint content
        String endpointsContent = Files.readString(endpointsFile);
        assertTrue(endpointsContent.contains("/api/hello"),
                "endpoints.jsonl should contain /api/hello endpoint");
        assertTrue(endpointsContent.contains("GET"),
                "endpoints.jsonl should contain GET method");

        // Verify graph.tsv contains expected edges
        assertTrue(graphContent.contains("stereotype"),
                "graph.tsv should contain stereotype edges");
        // Test classes implicitly extend Object; Spoon may not report it
        // Check for other expected edge types instead
        assertTrue(graphContent.contains("call") || graphContent.contains("instantiate"),
                "graph.tsv should contain call or instantiate edges");
    }

    @Test
    void resolveSourceRoots_singleModuleProject() {
        Path testProject = Path.of("src/test/resources/test-project");
        List<Path> roots = CodeGraphCli.resolveSourceRoots(testProject);
        assertFalse(roots.isEmpty(), "Should find at least one source root");
        assertTrue(roots.stream().anyMatch(p -> p.endsWith("src/main/java")),
                "Should contain src/main/java");
    }

    @Test
    void resolveSourceRoots_multiModuleProject() {
        Path multiModuleProject = Path.of("src/test/resources/multi-module-project");
        List<Path> roots = CodeGraphCli.resolveSourceRoots(multiModuleProject);

        assertTrue(roots.stream().anyMatch(p -> p.endsWith("module-a/src/main/java")),
                "Should contain module-a/src/main/java");
        assertTrue(roots.stream().anyMatch(p -> p.endsWith("module-b/src/main/java")),
                "Should contain module-b/src/main/java");
    }

    @Test
    void integrationTest_multiModuleProject(@TempDir Path tempDir) throws Exception {
        Path testProjectSrc = Path.of("src/test/resources/multi-module-project");
        copyDirectory(testProjectSrc, tempDir);

        cli.generateGraphDependencies(new String[]{tempDir.toString()});

        Path outDir = tempDir.resolve(".code-graph");
        assertTrue(Files.exists(outDir), "Output directory should exist");

        // Verify both modules' classes were analyzed
        Path moduleAServiceJson = outDir.resolve("classes/com.modulea.ModuleAService.json");
        assertTrue(Files.exists(moduleAServiceJson), "ModuleAService.json should exist");

        Path moduleBServiceJson = outDir.resolve("classes/com.moduleb.ModuleBService.json");
        assertTrue(Files.exists(moduleBServiceJson), "ModuleBService.json should exist");

        // Verify graph.tsv contains edges from both modules
        Path graphFile = outDir.resolve("graph.tsv");
        assertTrue(Files.exists(graphFile));
        String graphContent = Files.readString(graphFile);
        assertTrue(graphContent.contains("com.modulea.ModuleAService"),
                "graph.tsv should contain ModuleAService");
        assertTrue(graphContent.contains("com.moduleb.ModuleBService"),
                "graph.tsv should contain ModuleBService");
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            walk.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}