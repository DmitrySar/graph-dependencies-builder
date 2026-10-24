# AGENTS.md

## What this repo is

- Single-module Maven project: **Spring Boot 4.1.1 (Java 17)** CLI that statically analyzes a Java project with **Spoon** and writes a dependency index to `<analyzed-project>/.code-graph/`. It is not a web service; it exposes no endpoints of its own.
- Entry point: `src/main/java/com/example/graphbuilder/GraphbuilderApplication.java` — starts Spring, gets the `CodeGraphCli` bean, calls `generateGraphDependencies(args)`. `args[0]` = path to the project to analyze (default `.`).
- With `--mcp` flag the jar runs as an **MCP (Model Context Protocol) server over stdio** instead of a one-shot CLI: `CodeGraphMcpServer` implements newline-delimited JSON-RPC 2.0 (`initialize`, `tools/list`, `tools/call`, `ping`) and exposes tools `analyze_project`, `get_meta`, `get_graph` (filters: `edge_type`, `contains`), `get_called_by` (filter: `target`), `get_endpoints`, `list_classes`, `get_class` — each takes the analyzed project's `path` and reads its `.code-graph/`. In MCP mode stdout is reserved for the protocol; Spring logs go to `code-graph-mcp.log` (client config example in `HELP.md`).
- Nearly all logic is in `src/main/java/com/example/graphbuilder/CodeGraphCli.java` (5-stage pipeline: scan meta → build Spoon AST → extract graph → write outputs). An in-repo analysis of that class, incl. improvement list and verification steps, lives in `.gigacode/plans/*.plan.md`.

## Commands

```bash
mvn test                                    # build + tests (./mvnw test also works)
mvn test -Dtest='CodeGraphCliTest#tsv_returnsTabSeparatedString'   # single test
mvn -DskipTests package                     # → target/graphbuilder-0.0.1-SNAPSHOT.jar
java -jar ./target/graphbuilder-0.0.1-SNAPSHOT.jar <path-to-project>   # analyze a project
java -jar ./target/graphbuilder-0.0.1-SNAPSHOT.jar .                   # regenerate this repo's own .code-graph/
java -jar ./target/graphbuilder-0.0.1-SNAPSHOT.jar --mcp               # run as MCP server over stdio
```

- Verification order used in this repo (from `.gigacode/plans`): `./mvnw clean compile` → `./mvnw test` → `./mvnw spring-boot:run`.
- There is **no CI, linter, formatter, or typecheck config** — `mvn test` is the only gate.

## Known failure: tests fail on Linux/macOS (not your fault)

**Fixed 2026-10-24** — the hardcoded Windows path separator bug was resolved (path filtering now uses normalized `Path`), and `mvn test` is green on Linux. Kept below for history:

- `CodeGraphCliTest.integrationTest_withTestProject` used to fail on non-Windows: `buildModel` in `CodeGraphCli.java` filtered sources with the hardcoded Windows path `src\main\java`, so on Linux/macOS zero files reached Spoon → `graph.tsv` was empty.

## Navigating the code: use the `.code-graph/` index

Per `HELP.md` (Russian + Windows variants; this file is the condensed canonical version): **do not blanket-read Java sources**. Use the pre-generated index in `.code-graph/` plus `grep`/`rg` and targeted reads.

- `.code-graph/meta.json` — build system, Spring Boot version, base package.
- `.code-graph/endpoints.jsonl` — one JSON object per REST endpoint (`method`, `path`, `handler`, `location`). **Empty in this repo** (no controllers here).
- `.code-graph/graph.tsv` — edges: `FROM \t TYPE \t TO \t FILE:LINE \t NOTE` (types: `extends`, `implements`, `stereotype`, `autowire_field`, `call`, `instantiate`, `has_transaction`, `listens_to_event`, `declares_bean`).
- `.code-graph/called_by.tsv` — reverse calls: `TARGET \t CALLER \t FILE:LINE`.
- `.code-graph/classes/<FQCN>.json` — per-class methods/fields dump.

```bash
grep '"method":"POST"' .code-graph/endpoints.jsonl
grep -P '\tcall\t' .code-graph/graph.tsv | grep GraphbuilderApplication
```

The index is an **untracked build artifact** (like `.gigacode/` and `log/` — all untracked, none in `.gitignore`): it goes stale after edits. Regenerate by running the jar on `.` after a successful package.

## Gotchas

- **Spoon 10.3.0 + `ignoreSyntaxErrors(true)` silently drops files using switch expressions** (Java 14+ `switch (x) { case ... -> ... }`): the JDT recovery path logs a bogus "Syntax error detected in:" warning and the file never reaches the model. `CodeGraphCli.java` and `CodeGraphMcpServer.java` must avoid switch expressions — use if/else chains (diagnosed 2026-10-24).
- On the MCP path never use `System.out.println`: stdout carries the JSON-RPC protocol (console logging is disabled via `logging.console.enabled=false` in `--mcp` mode; logs go to `code-graph-mcp.log`).
- The analyzer only picks up files under `src/main/java` and skips `target/`, `.git/`, and `CodeGraphCli.java` itself. Files elsewhere (e.g. `src/test/java`, `src/test/resources`) are invisible to it.
- Running the jar against `src/test/resources/test-project` writes a stray `.code-graph/` **inside the test fixture** (not gitignored) — delete it; the JUnit integration test avoids this by copying the fixture to a `@TempDir`.
- Log messages and docs are in Russian; progress logs are labeled `[1/5]`…`[5/5]`. Match the existing style when touching log output.
- `GraphbuilderApplicationTests` is a `@SpringBootTest` context-load test; `main()` does **not** run during tests.
