# 仓库开发指南

## 项目与技术栈

智能运维平台是中文 AI/AIOps 工作台。后端使用 Java 17、Spring Boot 3.5.7 和 AgentScope 2.0.3；前端使用 Vue 3、TypeScript、Pinia 和 Vite。SQLite 保存用户业务数据与后台任务，Milvus 保存知识向量，Qwen 提供聊天、Embedding 和 rerank，官方 SLS MCP Server 提供日志查询工具。

开始修改前阅读 README.md、目标模块的实现及相关测试。以实际代码、配置模板和脚本为依据描述行为。

## 目录与职责

| 路径 | 职责 |
|---|---|
| `src/main/java/io/agentjava/AgentJavaApplication.java` | Spring Boot 入口与监听参数 |
| `src/main/java/io/agentjava/api/` | HTTP 路由、认证过滤、响应协议、SSE、健康检查和请求指标 |
| `src/main/java/io/agentjava/auth/` | 注册、登录、密码哈希、认证会话及注销 |
| `src/main/java/io/agentjava/config/` | JSON 配置读取、合并、校验及错误脱敏 |
| `src/main/java/io/agentjava/store/` | SQLite、SQL 迁移与 JSON 序列化 |
| `src/main/java/io/agentjava/jobs/` | 持久队列、租约、心跳、取消、重试和事件 |
| `src/main/java/io/agentjava/model/` | AgentScope 聊天模型及 Embedding/rerank 请求 |
| `src/main/java/io/agentjava/agent/` | 聊天、Prompt、Skill、记忆与工具审计 |
| `src/main/java/io/agentjava/knowledge/` | 文档处理、切分、索引、混合检索与向量存储 |
| `src/main/java/io/agentjava/mcp/` | MCP 连接、初始化、工具发现和调用 |
| `src/main/java/io/agentjava/aiops/` | 活跃告警、诊断、证据、报告、案例与巡检 |
| `src/main/java/io/agentjava/feedback/` | 用户反馈与目标归属检查 |
| `src/main/java/io/agentjava/fixtures/` | 显式开发数据命令 |
| `src/main/resources/db/migration/` | 按版本执行的 SQL 文件 |
| `src/test/java/` | 后端领域逻辑、接口及后台任务测试 |
| `apps/frontend/src/`、`apps/frontend/tests/` | 工作台与前端测试 |
| `packages/api-contracts/src/`、`packages/api-contracts/tests/` | HTTP、错误码、OpenAPI、SSE 和共享类型 |
| `scripts/`、`infra/`、`docs/` | 启动与检查、容器基础设施、使用文档 |
| `var/`、`target/` | 本地数据、日志及构建输出，均被 Git 忽略 |

## 配置与运行

- 应用读取 `config/project.json` 与 `config/user.project.json`，对象递归合并，数组和标量整体覆盖。两份文件均须存在；模板位于同一目录。
- 配置根目录默认为工作目录。Java 命令可通过 `-Dagentjava.root=/path/to/repository` 指定根目录；启动脚本自动切换到仓库根目录。
- 默认后端端口为 8081，启动器前端端口固定为 5175，SLS MCP 为 3000。修改后端端口时同时检查 `frontend.apiBaseUrl` 和 `aiopsDemo.backendBaseUrl`。
- 前端构建只注入 `frontend.title` 和 `frontend.apiBaseUrl`。真实配置、认证信息、`var/`、依赖目录及构建输出不提交。
- Compose 运行 etcd、MinIO、Milvus、Attu 和 Alertmanager。Java、Vite 与 SLS MCP 在主机运行。
- 启动器按端口是否监听决定复用或启动，不会自动重启现有服务。修改代码或配置后停止对应进程再运行启动器；后端运行期间不要覆盖其已打开的 jar。
- 普通启动只启动服务。上传开发日志、发布告警、创建演示账号和导入 SOP 必须使用显式开发数据命令。

## 实现约定

- 领域逻辑放在对应 Java 服务中，HTTP 层负责解析、鉴权和协议映射；模型、MCP 和向量存储通过各自模块访问。
- HTTP JSON 使用 `api/Protocol.java` 的统一 envelope 和错误码。流式接口使用共享契约中的事件类型，并保留请求 ID、事件顺序与结束状态。
- 用户身份取自 Bearer 认证。SQLite 资源、任务、消息、文档、Prompt、Skill、MCP、诊断、案例及反馈均检查 owner；向量检索和删除包含用户、租户及文档作用域。
- 密码使用 Argon2id，认证 token 仅保存摘要；注销使认证会话失效。错误与日志中的凭据通过配置和工具参数脱敏逻辑处理。
- 聊天使用 AgentScope ReActAgent 和已注册工具。Skill 目录提供名称与描述，正文通过 `load_skill` 按需读取；引用和工具审计属于当前轮消息。
- SQLite 原始消息保留完整历史，摘要单独存储。自动压缩模式为每 30 轮或上下文占用 70%；95% 上下文输入保护使用统一业务错误。
- 知识索引使用持久后台任务。Embedding 每批最多 10 条原始字符串，维度固定为 1024；检索保留 Milvus、BM25L、RRF 和 rerank 的排名及分数。
- SQL 结构变化新增递增版本的迁移文件，考虑已有数据库启动和事务失败场景。
- 后台任务保持租约、心跳、重试、超时、取消和检查点语义；SSE 断开不等同于取消任务。诊断限制工具执行和重规划次数，并在报告中关联实际证据。
- 测试桩用于确定性边界测试，运行时使用配置的真实服务。依赖失败必须返回明确状态；报告、日志、向量及来源不能伪造。
- 修改接口时检查共享类型、错误码、OpenAPI 与前端调用。`npm run openapi:export` 生成 `src/main/resources/openapi.json`，然后重新构建后端。

## 检查与验收

从仓库根目录执行与改动相关的命令：

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

`./scripts/check.sh` 提供仓库完整检查。Windows 可在 Git Bash 中执行，或逐项运行对应命令。文档改动检查链接、命令和构建；界面改动通过浏览器核验可见行为。外部服务验收脚本位于 `scripts/acceptance/`，运行条件和顺序见 README.md 与 docs/tutorials/real-log-and-alert.md。

只修改任务需要的文件，保留用户已有改动。验收说明区分自动测试、真实外部服务和实际运行平台；文档只描述本仓库的功能、配置及操作。
