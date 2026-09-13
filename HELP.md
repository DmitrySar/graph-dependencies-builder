# Создаёт граф зависимостей проекта

запуск: `java -jar ./target/graphbuilder-0.0.1-SNAPSHOT.jar путь_к_проекту`

---
Добавить в Agenent.md(GIGACODE.md)

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