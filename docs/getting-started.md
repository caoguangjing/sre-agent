# 本地运行指南

智能运维平台的 Java 后端、Vue 工作台和官方 SLS MCP 在主机运行。Docker Compose 管理 etcd、MinIO、Milvus、Attu 与 Alertmanager。

## 准备工具

安装 JDK 17+、Maven 3.9+、Node.js 22 LTS、npm、Docker 和 Compose v2。SLS MCP 还需要 uv 和阿里云 CLI（3.3.0 或更高），并执行一次 `aliyun configure --mode OAuth` 完成浏览器授权。确认 Docker 引擎处于运行状态：

```bash
java -version
mvn -version
node --version
npm --version
docker info
docker compose version
uv --version
aliyun version
```

各平台准备方法见 [macOS](setup/macos.md)、[Linux](setup/linux.md) 和 [Windows](setup/windows.md)。以下命令从仓库根目录执行。

## 配置

首次使用时创建两份本地 JSON；对应文件已存在时跳过复制。

```bash
cp config/project.template.json config/project.json
cp config/user.project.template.json config/user.project.json
```

Windows 命令提示符使用 `copy` 和反斜线路径：

```bat
copy config\project.template.json config\project.json
copy config\user.project.template.json config\user.project.json
```

编辑 `user.project.json`：

| 配置 | 需要填写或检查 |
|---|---|
| `llm.apiKey` | 有效的 Qwen API key |
| `llm.chatModel`、`llm.embeddingModel` | 账号可调用的模型 |
| `llm.modelCapabilities` | 以聊天模型名称为键，填写 `contextWindowTokens` |
| `llm.rerankModel`、`llm.rerankUrl` | 账号可调用的 rerank 模型及地址，默认值在基础模板 |
| `llm.rerankMinScore` | rerank 相关性分数下限（0~1），低于阈值的候选不进入检索结果；0 表示不启用，默认 0.6 |
| `llm.embeddingDimensions`、`vectorStore.vectorDimension` | 均为 1024 |
| `slsMcpServer.profile` | 阿里云 CLI 的 OAuth 配置名，默认 `default` |
| `slsLogUpload.accessKeyId`、`accessKeySecret`、`project`、`logstore` | 需要上传开发日志时填写实际日志 Project 与 Logstore |
| `prometheusAlerts.sources` | 有效的 Prometheus v1 / Alertmanager v2 来源；无外部 Prometheus 时只保留本地 Alertmanager |
| `aiopsDemo.password` | 需要导入演示 SOP 时填写 |

`user.project.json` 示例：

```json
{
  "llm": {
    "apiKey": "填写 Qwen API key",
    "chatModel": "qwen3.7-max",
    "embeddingModel": "text-embedding-v4",
    "modelCapabilities": {
      "qwen3.7-max": { "contextWindowTokens": 1000000 }
    }
  },
  "slsMcpServer": {
    "profile": "default"
  },
  "slsLogUpload": {
    "endpoint": "https://cn-hangzhou.log.aliyuncs.com",
    "accessKeyId": "填写阿里云 AccessKey ID",
    "accessKeySecret": "填写阿里云 AccessKey Secret",
    "project": "填写日志 Project",
    "logstore": "填写日志 Logstore"
  }
}
```

更换聊天模型时，`llm.modelCapabilities` 的键必须与 `llm.chatModel` 一致，窗口大小按实际模型填写。Embedding 与 Milvus 的维度均须为 **1024**。基础模板已提供聊天 API、rerank 地址与默认 rerank 模型；使用账号须具备对应调用权限。

两份 JSON 必须存在；对象递归合并，数组和标量由用户配置整体覆盖。默认后端为 8081，SQLite 为 `var/agent-java.db`，Milvus collection 为 `agent_java_knowledge`。

修改后端端口时同时调整 `frontend.apiBaseUrl` 和 `aiopsDemo.backendBaseUrl`；修改 SLS MCP 端口时同时调整 `mcp.slsSseUrl`。前端只注入标题和 API 地址。配置修改后重启对应进程。

主要配置项的模板默认值：

| 配置 | 模板默认值 / 用途 |
|---|---|
| `backend.host`、`backend.port` | `127.0.0.1`、`8081`，后端监听地址 |
| `backend.memoryDatabaseUrl` | `jdbc:sqlite:./var/agent-java.db`，业务数据库 |
| `frontend.title`、`frontend.apiBaseUrl` | 界面标题、`http://127.0.0.1:8081` |
| `llm.baseUrl`、`llm.rerankUrl` | DashScope 聊天/Embedding 与独立 rerank 地址 |
| `vectorStore.uri`、`vectorStore.collectionName` | `http://localhost:19530`、`agent_java_knowledge` |
| `slsMcpServer.port`、`mcp.slsSseUrl` | `3000`、`http://127.0.0.1:3000/sse` |
| `prometheusAlerts.sources` | Prometheus v1 或 Alertmanager v2 告警 API 来源 |
| `slsLogUpload` | 显式开发日志上传的 endpoint、AccessKey、Project 和 Logstore |
| `aiopsDemo` | SOP 导入使用的账号；需填写 password，backendBaseUrl 指向实际后端 |

没有外部 Prometheus 时，可在用户配置中只保留本地 Alertmanager。数组会整体替换：

```json
{
  "prometheusAlerts": {
    "sources": [
      {
        "id": "local-alertmanager",
        "type": "alertmanager-v2",
        "alertsApi": "http://127.0.0.1:9093/api/v2/alerts"
      }
    ]
  }
}
```

上述示例应与自己的其他字段放在同一个 JSON 对象中。

MCP 连接页面中的超时适用于普通工具。`mcp.toolTimeoutSeconds` 可为慢工具设置独立超时下限，实际调用采用连接超时与工具下限中的较大值，最高 300 秒。默认 `sls_query_logs` 为 60 秒；真实日志查询 15 秒可能不足：

```json
{
  "mcp": {
    "toolTimeoutSeconds": { "sls_query_logs": 60 }
  }
}
```

## 启动

macOS / Linux：

```bash
./scripts/start-local.sh
```

Windows：

```bat
.\scripts\start-local.bat
```

启动器自动安装缺失的 Node 依赖，按端口检查并启动容器、MCP、Java 后端和 Vite。Java 未运行时会先打包。它不创建配置文件，也不重启已监听的服务；修改代码或配置后，先停止受影响的进程再运行启动器。端口可连接仅表示监听成功，依赖是否可用需做就绪检查。

启动器的实际顺序：

1. 读取两份 JSON，创建 `var/`。缺少 `node_modules/` 时执行 `npm ci`。
2. 检查本机 19530；未监听时启动 etcd、MinIO、Milvus、Attu 并等待容器健康。
3. 检查本机 9093；未监听时启动 Alertmanager 并等待健康。
4. 检查配置的 SLS MCP 端口；未监听时启动官方 MCP。
5. 检查配置的后端端口；未监听时执行 Maven 打包，再后台运行 jar。
6. 检查 5175；未监听时后台启动 Vite。最后分别等待后端和前端端口，最多各 60 秒。

| 服务 | 默认地址 |
|---|---|
| 工作台 | <http://127.0.0.1:5175> |
| Java API | <http://127.0.0.1:8081> |
| 后端存活 / 就绪 | <http://127.0.0.1:8081/health> / <http://127.0.0.1:8081/ready> |
| SLS MCP SSE | <http://127.0.0.1:3000/sse> |
| Alertmanager | <http://127.0.0.1:9093> |
| Milvus SDK/gRPC | `localhost:19530` |
| Milvus 健康检查 | <http://127.0.0.1:9091/healthz> |
| Attu | <http://127.0.0.1:8001> |
| MinIO 控制台 | <http://127.0.0.1:9001> |

Milvus 的 19530 是 SDK/gRPC 端口。容器映射和镜像以 `infra/compose.yaml` 为准，Compose 使用其中的固定镜像。

## 分别启动

已有基础设施或需要前台查看输出时，先安装依赖：

```bash
npm ci
docker compose -f infra/compose.yaml up -d --wait etcd minio milvus attu alertmanager
node scripts/start-mcp.mjs
```

已有对应服务时跳过其启动命令。MCP 在后台运行，PID 输出到终端，日志写入 `var/mcp.log`；官方 SLS MCP 只提供 stdio 传输，启动脚本用 supergateway 将其桥接为本机 SSE。

在一个终端运行后端：

```bash
mvn -q -DskipTests package
java -jar target/agent-java-0.1.0.jar
```

在另一个终端运行前端：

```bash
npm --workspace apps/frontend run dev -- --host 127.0.0.1 --port 5175 --strictPort
```

后端启动自动执行 SQLite SQL 迁移。Java 默认从工作目录读取配置，也可通过 `-Dagentjava.root=/path/to/repository` 指定根目录。

## 确认就绪

```bash
curl -i http://127.0.0.1:8081/health
curl -i http://127.0.0.1:8081/config/check
curl -i http://127.0.0.1:8081/ready
curl -i http://127.0.0.1:8081/health/mcp
```

Windows PowerShell 使用 `curl.exe`。接口均位于后端根路径，不带 `/api` 前缀。存活检查不请求外部服务；配置和就绪检查真实访问 SQLite、Milvus、Qwen 与 MCP。成功返回 200，依赖或配置失败返回 503，具体结果见 `data.dependencies`。

注册账号后，在知识库上传文档并等待索引；在 MCP 页面新建、检查并启用连接；在聊天中选择 Prompt、Skill 并提问。诊断需要活动告警、可检索的 SOP 和真实日志输入。基础配置中的 MCP 地址用于依赖检查；账号下用于聊天和诊断的连接需在 MCP 页面单独创建。需要认证的 MCP 服务在连接表单填写自定义 Header（如 `Authorization: Bearer <token>`），值以掩码显示，重新输入完整值才会覆盖；诊断在仅有日志或指标类工具时同样可运行，不强制要求 SLS。

## 日志与重启

自动启动的后端和前端分别记录 `var/backend.log`、`var/frontend.log`，PID 文件为 `var/backend.pid`、`var/frontend.pid`。MCP 只记录日志，PID 由命令输出。手动前台服务用 Ctrl+C 停止。

修改后端代码或配置时，先核对并停止 Java 进程，再运行启动器。后端运行期间不要覆盖已打开的 jar。PID 文件可能残留，停止前核对命令行。详细操作见 [运维与监控](operations-and-monitoring.md)。

## 验证与联调

```bash
./scripts/check.sh
```

此脚本使用 Bash；Windows 可用 Git Bash 或逐项执行 README 中的检查命令。

[日志与告警联调](tutorials/real-log-and-alert.md) 说明显式开发数据操作和真实服务验收顺序。普通启动不上传日志、不发布告警、不创建演示账号或导入 SOP。
