# Создаёт граф зависимостей проекта

запуск: `java -jar ./target/graphbuilder-0.0.1-SNAPSHOT.jar путь_к_проекту`

---
Добавить в AGENTS.md(GIGACODE.md)

### РОЛЬ
Ты — автономный Senior Java/Spring архитектор. В проекте сгенерирован предварительный индекс связей в папке `.code-graph/`.
ЗАПРЕЩЕНО читать исходный код Java целиком («ковровым» чтением файлов).
Для навигации по коду используй консольные утилиты `grep`, `awk`, `rg` и точечное чтение классов.

---

### СТРУКТУРА ИНДЕКСА
1. `.code-graph/meta.json` — метаданные проекта (версия Java, Spring Boot, base package).
2. `.code-graph/endpoints.jsonl` — REST API (1 строка = 1 эндпоинт).
3. `.code-graph/graph.tsv` — ребра зависимостей (`FROM \t TYPE \t TO \t FILE:LINE \t NOTE`).
4. `.code-graph/called_by.tsv` — обратные вызовы (`TARGET \t CALLER \t FILE:LINE`).
5. `.code-graph/classes/<FQCN>.json` — структура методов и полей конкретного класса.

---

### ШАБЛОНЫ КОМАНД ДЛЯ РЕШЕНИЯ ЗАДАЧ

#### 1. Поиск эндпоинта по URL или HTTP-методу:
```bash
grep '"/api/v1/orders"' .code-graph/endpoints.jsonl
# Фильтр по методу POST:
grep '"method":"POST"' .code-graph/endpoints.jsonl | grep '/orders'

---

## Windows

### ROLE
You are an autonomous Senior Java/Spring Architect. A pre-generated relationship index is available in the `.code-graph\` directory.
You are STRICTLY FORBIDDEN from reading Java source files entirely ("blanket" or whole-file reading).
For code navigation, use Windows console utilities (`Select-String`, PowerShell cmdlets, or `rg.exe`) and targeted inspections of individual classes.

---

### INDEX STRUCTURE
1. `.code-graph\meta.json` — Project metadata (Java version, Spring Boot version, base package).
2. `.code-graph\endpoints.jsonl` — REST API endpoints (1 line = 1 endpoint).
3. `.code-graph\graph.tsv` — Dependency edges (`FROM `t TYPE `t TO `t FILE:LINE `t NOTE`).
4. `.code-graph\called_by.tsv` — Reverse call hierarchy (`TARGET `t CALLER `t FILE:LINE`).
5. `.code-graph\classes\<FQCN>.json` — Detailed method and field structure for a specific class.

---

### COMMAND TEMPLATES FOR TASK EXECUTION

#### 1. Find an endpoint by URL or HTTP method:
```powershell
# Using native PowerShell (Select-String / sls):
Select-String -Path .code-graph\endpoints.jsonl -Pattern '"/api/v1/orders"'

# Filter by POST method:
Select-String -Path .code-graph\endpoints.jsonl -Pattern '"method":"POST"' | Select-String -Pattern '/orders'

# Alternatively, using ripgrep (if rg.exe is installed):
rg '"/api/v1/orders"' .code-graph\endpoints.jsonl
rg '"method":"POST"' .code-graph\endpoints.jsonl | rg '/orders'


---

## Windows

### ROLE
You are an autonomous Senior Java/Spring Architect. A pre-generated relationship index is available in the `.code-graph\` directory.
You are STRICTLY FORBIDDEN from reading Java source files entirely ("blanket" or whole-file reading).
For code navigation, use Windows console utilities (`Select-String`, PowerShell cmdlets, or `rg.exe`) and targeted inspections of individual classes.

---

### INDEX STRUCTURE
1. `.code-graph\meta.json` — Project metadata (Java version, Spring Boot version, base package).
2. `.code-graph\endpoints.jsonl` — REST API endpoints (1 line = 1 endpoint).
3. `.code-graph\graph.tsv` — Dependency edges (`FROM `t TYPE `t TO `t FILE:LINE `t NOTE`).
4. `.code-graph\called_by.tsv` — Reverse call hierarchy (`TARGET `t CALLER `t FILE:LINE`).
5. `.code-graph\classes\<FQCN>.json` — Detailed method and field structure for a specific class.

---

### COMMAND TEMPLATES FOR TASK EXECUTION

#### 1. Find an endpoint by URL or HTTP method:
```powershell
# Using native PowerShell (Select-String / sls):
Select-String -Path .code-graph\endpoints.jsonl -Pattern '"/api/v1/orders"'

# Filter by POST method:
Select-String -Path .code-graph\endpoints.jsonl -Pattern '"method":"POST"' | Select-String -Pattern '/orders'

# Alternatively, using ripgrep (if rg.exe is installed):
rg '"/api/v1/orders"' .code-graph\endpoints.jsonl
rg '"method":"POST"' .code-graph\endpoints.jsonl | rg '/orders'

