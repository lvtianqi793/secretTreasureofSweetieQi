# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

建筑能源智能管理与运营优化系统 — a three-tier energy management system built for the 服务外包大赛 competition. It ingests multi-energy-source CSV data (electricity / water / gas / steam / chilledwater / hotwater / solar / irrigation / weather), exposes REST APIs for querying and statistics, and layers an LLM-powered Q&A / SQL-generation / RAG-analysis frontend on top.

## Architecture

Three services, all joined to an **external** Docker network named `docker_ragflow` (it is not created by this project — it is owned by a separately-run Ragflow stack):

- **`backend/`** — Spring Boot 3.2.5 / Java 17 / JPA / PostgreSQL. Listens on `:8080` with `server.servlet.context-path=/api`, so every route is prefixed with `/api`. Controllers: `AiChat`, `DataImport`, `EnergyQuery`, `Statistics`, `System`. Knife4j/OpenAPI docs enabled. Uses OkHttp to call the AI gateway, OpenCSV for import, Apache POI for Excel export.
- **`ai/mvp-ollama-gateway/`** — FastAPI 0.115 / Python 3.9 / `fastmcp`. Listens on `:8000`. Four SSE generation endpoints differentiated by prompt type and backend:
  - `/generate/chat` → Ollama + `system_chat_prompt.txt` (pure chat, no data)
  - `/generate/generatesql` → Ollama + `system_generatesql_prompt.txt` (NL → SQL)
  - `/generate/analyse` → **Ragflow** when `RAGFLOW_ENABLED=true`, with automatic fallback to Ollama + `system_analyse_prompt.txt` on `RagflowNoDocumentsException`
  - `/generate/agent` → MCP tool-calling agent; LLM drives tools (`query_energy_data`, `summary_statistics`, `calculate_cop`, `detect_anomaly`, `generate_chart`) that call back into Spring Boot REST
  - Also exposes `/mcp/sse` (MCP SSE transport) and `/exports/*` (static files written by MCP export tools).
- **`frontend/`** — Vue 3.5 + Vite 6 + TypeScript. Dev server proxies `/api` → `http://127.0.0.1:8080` (see `vite.config.ts`). Uses ECharts for charts, `marked` + `dompurify` for rendering LLM markdown safely.
- **`db/`** — PostgreSQL 15-alpine image + `init.sql` + `import-data.sh`. The import script matches CSV filenames by keyword (`*electricity*` → `electricity_data`, etc.) and writes to `import_log` table on completion. On success it creates `/data/status/data_import_complete` which gates the runtime DB healthcheck.

### External dependencies not in the repo

- **Ollama on the host** — required models: `qwen3.5:9b` (chat) and `mxbai-embed-large:latest` (embedding). Containers reach it via `172.17.0.1:11434` (default `OLLAMA_URL`).
- **Ragflow stack** — `start.sh` expects it at `${HOME}/myRagflow/ragflow/docker` and brings it up before this project's compose. The AI gateway calls it at `http://docker-ragflow-cpu-1:9380` over the shared network.

### Two-phase Docker bring-up (important)

The repo ships **two** compose files with a deliberate ordering constraint:

1. `compose.data.yaml` — builds `db/Dockerfile`, mounts `./data/csv:/data/csv:ro`, runs CSV import, exits. Persists data into the `postgres-data` volume and the completion marker into the `import-status` volume.
2. `compose.yaml` — runs `energy-backend`, `postgres-db` (stock `postgres:15-alpine`, **not** the `db/` build), and `ai-gateway`. Its `postgres-db` healthcheck fails unless `/data/status/data_import_complete` exists — so phase 1 must run first on a fresh volume.

`start.sh` orchestrates the runtime side only (`docker compose up --build -d` on `compose.yaml`); the data-import phase is expected to have been run manually beforehand.

## Common commands

### Full stack (Linux/WSL only — `start.sh` is bash)

```bash
./start.sh start      # ollama → ragflow → backend+db+ai-gateway
./start.sh stop
./start.sh restart
./start.sh status     # port/container health summary
```

### First-time / data re-import

```bash
# After placing CSVs in ./data/csv/ (filenames must contain the table keyword)
docker compose -f compose.data.yaml up --build
# Then the regular stack:
docker compose up --build -d
```

### Env files

Copy the three examples before first run:

```bash
cp .env.example.aiapi   .env.aiapi
cp .env.example.backend .env.backend
cp .env.example.db      .env.db
```

### Backend (Spring Boot)

```bash
cd backend
./mvnw spring-boot:run         # or: mvn spring-boot:run
mvn clean package -DskipTests  # mirrors the Dockerfile build
mvn test                       # full test run
mvn -Dtest=ClassName#method test
```

Swagger UI: `http://localhost:8080/api/doc.html` (Knife4j). Actuator health: `/actuator/health`.

### AI gateway (FastAPI)

```bash
cd ai/mvp-ollama-gateway
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

OpenAPI docs: `http://localhost:8000/docs`. Health: `/health`. When running outside Docker, set `MCP_SSE_URL=http://localhost:8000/mcp/sse` (the example file has the in-container value).

### Frontend (Vue 3)

```bash
cd frontend
npm install
npm run dev        # vite dev server, proxies /api → :8080
npm run build      # vue-tsc -b && vite build
npm run preview
```

## Conventions worth knowing

- **Every backend route is under `/api`** because of `server.servlet.context-path`. When adding controllers, do **not** prefix paths with `/api` again.
- **JPA `ddl-auto=update`** is on in both `application.yml` and `.env.example.backend`. Schema changes via entity edits propagate automatically but coexist with the hand-written `db/init.sql` — keep them consistent.
- **Three prompt files** in `ai/mvp-ollama-gateway/prompts/` correspond 1:1 to the three non-agent `/generate/*` endpoints; each is loaded by a separately-keyed `prompt_loader` instance. Don't reuse one prompt across types.
- **Agent / MCP path** is different: `/generate/agent` ignores the static prompts and instead drives tools defined in `app/mcp_server/tools.py`, which in turn hit Spring Boot via `SPRING_BOOT_BASE_URL`. Bugs in agent responses are usually in the tool schemas or the Spring Boot endpoints they wrap, not in the Ollama call itself.
- **Ragflow fallback** is silent and automatic — if the analyse endpoint starts returning answers that look like pure Ollama output, the fallback has triggered (check logs for `RagflowNoDocumentsException`).
- CSV files are **.gitignored**; `data/csv/` is expected to exist locally. The import logic is filename-driven — renaming a CSV changes which table it populates.
