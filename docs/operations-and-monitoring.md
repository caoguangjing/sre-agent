# 运维与监控

## 进程与日志

自动启动命令为 `./scripts/start-local.sh`，Windows 为 `.\scripts\start-local.bat`。后端与前端分别在 `var/backend.log`、`var/frontend.log` 写日志，PID 文件为 `var/backend.pid`、`var/frontend.pid`。SLS MCP 日志为 `var/mcp.log`，启动命令输出 PID，没有 MCP PID 文件。

macOS / Linux：

```bash
tail -f var/backend.log
ps -p "$(cat var/backend.pid)" -o pid,args
ps -p "$(cat var/frontend.pid)" -o pid,args
lsof -nP -iTCP:3000 -sTCP:LISTEN
```

核对进程属于本项目后停止对应服务：

```bash
kill "$(cat var/backend.pid)"
kill "$(cat var/frontend.pid)"
```

MCP 使用启动命令输出或端口查询得到的 PID。手动前台启动的服务使用 Ctrl+C。PID 文件可能残留；Windows PID 可能属于包装进程，需核对实际监听进程及其子进程。

Windows PowerShell：

```powershell
Get-Content .\var\backend.log -Tail 50
Get-NetTCPConnection -State Listen -LocalPort 8081,5175,3000 |
  Select-Object LocalPort,OwningProcess
# 核对进程后执行：
Stop-Process -Id <PID>
```

修改后端代码或配置时先停止 Java，再构建和启动；后端运行期间不要覆盖已打开的 jar。启动器不会重启已经监听的进程。

## 服务检查

| 接口 | 检查内容 |
|---|---|
| `GET /health` | 进程存活，无外部请求 |
| `GET /ready` | 配置校验和 SQLite、Milvus、Qwen、MCP 依赖状态 |
| `GET /config/check` | 配置结果与同一组真实依赖检查 |
| `GET /health/mcp` | 初始化 MCP 并读取工具目录 |
| `GET /metrics` | HTTP envelope 中的请求量、失败量和平均耗时 JSON |

接口位于根路径，无 `/api` 前缀，无需认证。就绪或配置检查成功返回 200，失败返回 503。查看 `data.dependencies` 中各项的 ok、延迟与错误；顶层 envelope 的 ok 不替代依赖状态。

HTTP 和任务完成日志为结构化 JSON，包含 requestId、path、status、durationMs 或 jobId、kind、attempt。用户正文、query、模型内容及认证凭据不写入运行日志；业务工具审计及诊断证据按用户作用域保存，敏感参数字段递归脱敏。

## 数据与恢复

SQLite 默认位于 `var/agent-java.db`，结构迁移在启动时按版本执行。备份时先停止 Java，保存数据库及存在的 WAL/SHM 文件。Milvus 数据位于 Compose 的 milvus-data 卷；完整知识库恢复需同时考虑业务记录和向量数据。

SQLite 与 Milvus 没有跨系统事务。索引失败保留明确状态，修复依赖后通过页面重试。删除文档先取消活动索引，再按用户作用域删除向量并更新业务记录。

后台任务保存租约、心跳、尝试次数与事件。诊断保存阶段检查点；关闭浏览器或断开 SSE 不会取消后台任务，取消使用业务接口或页面操作。

普通启动只启动服务。上传日志、发布开发告警和导入 SOP 的操作见 [日志与告警联调](tutorials/real-log-and-alert.md)。

## 常见问题

| 现象 | 检查方法 |
|---|---|
| 启动报 JSON 文件缺失或无效 | 确认两份本地配置存在、JSON 语法正确，启动目录为仓库根目录 |
| Maven 编译失败 | 核对 `java -version` 与 `mvn -version` 使用的 JDK，确保不低于 17，检查依赖下载网络 |
| Docker 服务无法启动 | 先执行 `docker info`，查看 `docker compose -f infra/compose.yaml ps` 和相应容器日志 |
| 脚本返回但页面/API 内容不对 | 启动器复用了占用端口的服务；核对 5175、8081、19530、9093、3000 上的实际进程 |
| `/ready` 或 `/config/check` 返回 503 | 阅读 `data.configuration` / `data.dependencies`，核对模型权限、窗口、Milvus 地址与 MCP 服务 |
| 普通聊天可用，文档索引失败 | 核对 Embedding 模型权限、1024 维配置、Milvus 健康状态和任务错误 |
| 检索失败或 rerank 报错 | 核对 rerank 模型、地址与账号权限，检查索引状态及引用的文档是否属于当前账号 |
| 没有活动告警 | 检查 `prometheusAlerts.sources` 和源 API；开发告警会过期，按需重新发布 |
| 健康检查有 MCP 工具，聊天/诊断没有 | 在当前账号下创建、检查并启用 MCP 连接 |
| SLS 没有匹配日志 | 检查 Project、Logstore、时间范围、查询条件以及日志索引是否已可见 |
| 修改配置没有生效 | 停止相应服务再启动；已有监听端口不会被启动器重启 |
