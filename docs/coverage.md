# 功能与实现索引

下表列出当前功能模块和主要实现。服务端代码路径以 `src/main/java/io/agentjava/` 为基准，表中完整仓库路径除外。

|编号|功能|实现|
|---|---|---|
|P01|工程与配置|`config/ProjectConfig.java`、`pom.xml`、`scripts/launch-local.mjs`|
|P02|HTTP及SSE协议|`api/Protocol.java`、`api/ApiController.java`、`packages/api-contracts/src`|
|P03|SQLite迁移与存储|`store/Store.java`、`src/main/resources/db/migration`|
|P04|用户认证|`auth/AuthService.java`、`apps/frontend/src/stores/auth.ts`|
|P05|用户隔离|`store/Store.java`、`knowledge/VectorStore.java`、`api/RequestFilter.java`|
|P06|Qwen模型边界|`model/QwenProvider.java`|
|P07|本地基础设施|`infra/compose.yaml`、`knowledge/VectorStore.java`|
|P08|中文工作台|`apps/frontend/src/layouts`、`apps/frontend/src/router`、`apps/frontend/src/views`|
|P09|持久任务运行时|`jobs/JobRuntime.java`、`src/main/resources/db/migration/V2.sql`|
|P10|文档及切分|`knowledge/KnowledgeService.java`、`knowledge/DocumentSplitter.java`|
|P11|持久索引|`knowledge/KnowledgeService.java`、`jobs/JobRuntime.java`|
|P12|混合检索|`knowledge/KnowledgeService.java`、`agent/ChatService.java`|
|P13|知识库页面|`apps/frontend/src/views/KnowledgeView.vue`、`apps/frontend/src/components/KnowledgeUpload.vue`|
|P14|持久聊天|`agent/ChatService.java`|
|P15|Agentic RAG与审计|`agent/ChatService.java`、`agent/AuditedTool.java`|
|P16|Prompt及Skill|`agent/AssetService.java`、`agent/ChatService.java`|
|P17|记忆与预算|`agent/ChatService.java`|
|P18|真实MCP|`mcp/McpService.java`、`agent/AuditedTool.java`|
|P19|聊天桌面交互|`apps/frontend/src/views/ChatView.vue`、`apps/frontend/src/stores/chat.ts`|
|P20|告警与SLS输入|`aiops/AlertService.java`、`fixtures/FixtureCli.java`|
|P21|可恢复诊断与证据|`aiops/DiagnosisService.java`、`jobs/JobRuntime.java`|
|P22|案例知识闭环|`aiops/DiagnosisService.java`、`knowledge/KnowledgeService.java`|
|P23|诊断桌面交互|`apps/frontend/src/views/AiopsView.vue`、`apps/frontend/src/stores/aiops.ts`|
|P24|统一反馈|`feedback/FeedbackService.java`、`apps/frontend/src/components/UserFeedbackControl.vue`|
|P25|十套Java电商夹具|`fixtures/FixtureCli.java`、`src/main/resources/fixtures/incidents.json`|
|P26|可观测性与运维|`api/RequestFilter.java`、`api/ApiController.java`、`scripts/start-local.sh`|
|P27|功能设计文档|docs/changes/|

代码路径以src/main/java/io/agentjava为基准，除显式标出的仓库路径。全部服务端业务逻辑为Java；Node用于Vue工作台、文档及官方SLS MCP宿主。

## 验证入口

- Java 后端：`mvn -q test`。
- 共享接口契约：`npm run contracts:typecheck`、`npm run contracts:test`。
- Vue 工作台：`npm run frontend:typecheck`、`npm run frontend:test`、`npm run frontend:build`。
- 文档：`npm run docs:build`。
- 外部服务联调：见[日志与告警联调](tutorials/real-log-and-alert.md)。
