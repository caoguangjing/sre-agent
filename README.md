# 智能运维平台

智能运维平台是中文 AI/AIOps 工作台。Spring Boot 提供 HTTP API 和 SSE，AgentScope 驱动聊天工具调用与诊断推理，Vue 3 提供操作界面。SQLite 保存会话、文档、证据和后台任务，Milvus 保存知识向量，官方 SLS MCP Server 提供阿里云日志查询工具，并支持用户自定义 MCP 连接接入更多工具。

## 功能

| 模块 | 能力 |
|---|---|
| 账号 | 注册、登录、注销、认证恢复，用户资源隔离 |
| 聊天 | 持久会话、AgentScope ReAct 工具调用、SSE、工具审计、引用和反馈 |
| Prompt 与 Skill | 用户 Prompt 管理、标准 SKILL.md 上传、选择与按需正文加载 |
| 会话记忆 | 手动压缩、每 30 轮压缩、70% 上下文占用自动压缩，摘要与原始历史分别保存 |
| 知识库 | Markdown/PDF 上传、切分预览、重复检测、覆盖、删除、后台索引及重试 |
| 检索 | 1024 维 Embedding、Milvus/BM25L 并行召回、RRF 融合、Qwen rerank 与来源排名 |
| MCP | 用户连接管理、SSE/Streamable HTTP、自定义认证 Header（脱敏回显）、工具发现、检查、超时和重试 |
| 智能诊断 | 活跃告警、规划、工具执行、重规划、报告、真实日志/指标证据（SLS 或自定义 MCP）、Skill 指引、历史及案例库 |
| 告警接入 | Sunfire 回调接收（query token 鉴权）与定时拉取（queryAlarmList），与既有告警源合并为活跃告警，TTL 过期 |
| 智能巡检 | 定时扫描应用指标对比基线窗口，连续两轮同向偏移确认异常并自动触发诊断，恢复自动闭环 |
| 后台任务 | SQLite 持久队列、租约、心跳、恢复、取消、重试和事件回放 |
| 运行状态 | 存活、依赖就绪、配置检查、JSON 请求指标与结构化日志 |

## 快速开始

需要 JDK 17+、Maven 3.9+、Node.js 22 LTS、Docker Compose v2；使用官方 SLS MCP 还需 uv 与阿里云 CLI（3.3.0 或更高，完成一次 OAuth 登录）。安装步骤见 [macOS](docs/setup/macos.md)、[Linux](docs/setup/linux.md)、[Windows](docs/setup/windows.md)。

首次使用创建本地配置（本地配置不入 Git；Windows 命令提示符使用 `copy` 与反斜线路径）：

```bash
cp config/project.template.json config/project.json
cp config/user.project.template.json config/user.project.json
```

编辑 `config/user.project.json`，至少填写 Qwen `llm.apiKey`，再按需配置模型、SLS 日志上传与告警来源。Embedding 与 Milvus 维度固定 1024。完整配置说明见 [运行指南](docs/getting-started.md)。

启动 Docker 引擎后一键启动（Windows 为 `.\scripts\start-local.bat`）：

```bash
./scripts/start-local.sh
```

启动器自动安装依赖，按端口启动 Compose 容器（etcd、MinIO、Milvus、Attu、Alertmanager）、SLS MCP、Java 后端与 Vite 前端；不创建配置文件，也不重启已监听的进程。

| 服务 | 地址 |
|---|---|
| 工作台 | <http://127.0.0.1:5175> |
| Java API | <http://127.0.0.1:8081> |
| 就绪检查 | <http://127.0.0.1:8081/ready> |
| SLS MCP SSE | <http://127.0.0.1:3000/sse> |

就绪检查返回 200 即可打开工作台注册使用；返回 503 时按 `data.dependencies` 排查，完整服务地址与排查方法见 [运维与监控](docs/operations-and-monitoring.md)。

典型使用路径：知识库上传 Markdown/PDF 并等待索引 → MCP 页创建 SSE 连接并启用 → 聊天中选择 Prompt、Skill 提问 → 智能诊断选择活动告警启动。首次启动不会自动导入知识、发布告警或写入日志；开发数据与真实服务验收见 [日志与告警联调](docs/tutorials/real-log-and-alert.md)。

## 检查与构建

完整检查（macOS / Linux 或 Git Bash）：

```bash
./scripts/check.sh
```

按模块执行：

```bash
mvn -q test
npm run contracts:typecheck
npm run contracts:test
npm run frontend:typecheck
npm run frontend:test
npm run frontend:build
npm run docs:build
docker compose -f infra/compose.yaml config --quiet
git diff --check
```

后端测试使用临时配置、SQLite 和外部依赖桩，真实云服务验收另行执行。接口契约调整后执行 `npm run openapi:export` 并重新打包后端。

## 仓库结构

```text
src/main/java/io/agentjava/    Java API、领域服务、外部服务及开发数据命令
src/main/resources/           SQL 迁移、OpenAPI、应用资源
src/test/java/io/agentjava/    后端测试
apps/frontend/               Vue 工作台与测试
packages/api-contracts/       HTTP、OpenAPI、错误及 SSE 契约
config/                      配置模板（本地配置不入库）
infra/                       Compose 与 Alertmanager 配置
scripts/                     启动、检查、联调与文档工具
docs/                        使用指南、架构与运维
var/                         本地数据库、日志与验收输出（生成，不入库）
target/                      Java 构建产物（生成，不入库）
```

## 文档

[运行指南](docs/getting-started.md) · [系统架构](docs/architecture.md) · [功能索引](docs/coverage.md) · [运维与监控](docs/operations-and-monitoring.md) · [日志与告警联调](docs/tutorials/real-log-and-alert.md) · [设计记录](docs/changes/index.md)
