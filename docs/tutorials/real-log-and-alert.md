# 日志与告警联调

本教程使用人工构造的电商事故数据，通过真实 SLS、Alertmanager、Qwen 和 Milvus 检查完整诊断链路。日志上传会写入配置的真实 SLS Logstore，告警发布会写入本地 Alertmanager；所有命令均需显式执行。

## 前提

1. 按 [运行指南](../getting-started.md) 启动服务，确认 /config/check 各项依赖可用。
2. 已完成 `aliyun configure --mode OAuth` 一次登录，授权账号可查询目标 Logstore。
3. slsLogUpload.endpoint、accessKeyId、accessKeySecret、project、logstore 与实际日志一致，索引支持所需查询字段。
4. prometheusAlerts.sources 包含可访问的本地 Alertmanager。
5. 导入演示 SOP 时填写 aiopsDemo.email、password、displayName，backendBaseUrl 指向当前后端。

以下命令从仓库根目录执行。Windows 可使用 Git Bash；原生 PowerShell 调用 Java 的方式见 [Windows 指南](../setup/windows.md)。

## 1. 生成与发布数据

```bash
./scripts/fixtures.sh generate 30
./scripts/fixtures.sh upload 30
./scripts/fixtures.sh alerts
```

| 命令 | 行为 |
|---|---|
| generate | 在 var/fixtures 写入 10 套 SOP 和 30 条结构化日志 |
| upload | 通过官方阿里云 SLS Java SDK 生成并上传指定数量的合成日志 |
| alerts | 向 http://127.0.0.1:9093/api/v2/alerts 发布 10 条约一小时有效的告警 |
| seed | 登录或注册 aiopsDemo 账号，上传 10 套 SOP 并发起索引 |

日志数量范围为 1–100。upload 会生成本次日志，不读取 generate 生成的 JSON；数据包含 service、incident_id、trace_id 等关联标识。发布后等待 SLS 索引可查询。

## 2. 导入 SOP

```bash
./scripts/fixtures.sh seed
```

登录配置的演示账号，检查知识库中的 SOP 与索引状态。也可以使用自己的账号，在页面上传 var/fixtures 中的 Markdown 文件并发起索引。

## 3. 页面诊断

1. 登录与 SOP 归属相同的账号。
2. 在 MCP 页面创建 SSE 连接 http://127.0.0.1:3000/sse，检查并启用。
3. 在智能诊断页面选择活动告警，填写 SLS Project、Logstore、最近一小时的时间范围和对应 incident_id。
4. 发起只读诊断，检查 SOP 检索、计划、执行、重规划和报告。
5. 查看真实工具调用、日志证据与报告关联；成功诊断会生成案例并安排知识索引。
6. 在聊天中询问对应事故，检查案例来源及引用。

账号下的 MCP 连接需要单独创建；全局依赖检查通过不代表账号已启用工具。开发数据只验证联调链路，不代表实际生产事故。

诊断查询的 Project 和 Logstore 优先使用问题中指定的 `Project`、`Logstore`，其次使用告警注释中的 `slsProject`、`slsLogstore`，最后使用 `slsLogUpload` 配置。时间范围可填写 `From=Unix秒时间戳 To=Unix秒时间戳` 或两个 ISO 日期时间；不带时区的日期按北京时间解析。未指定时，告警诊断查询告警开始前 5 分钟至任务创建时间，普通诊断默认最近 15 分钟，也支持“最近2小时”等描述。查询参数在调用前校验，占位文字不会发送到 MCP。

没有匹配日志或外部调用失败时，任务仍标记失败，并保存包含失败原因、实际工具调用和已取得证据的分析报告，根因标记为未确认；失败任务不会自动生成案例。已有失败任务在后端启动时补齐缺失报告，可从诊断历史重新打开。修正查询范围或连接配置后可重新诊断。

SLS 查询语法或参数类型错误会触发一次模型查询修正，并在剩余工具执行预算内重新调用；修正后的查询保留原始检索条件、Logstore 和时间范围。语法错误不会在 MCP 连接层重复发送相同参数。个别工具失败时，报告保留失败记录和分析限制；只将实际成功返回的数据关联为证据。工具调用和证据列表默认折叠，展开后可查看原始日志及 SQL 聚合结果的摘要。

## 4. 自动验收

服务运行、开发日志和告警可见后依次执行：

```bash
node scripts/acceptance/smoke.mjs http://127.0.0.1:8081
node scripts/acceptance/mcp.mjs
node scripts/acceptance/aiops.mjs
node scripts/acceptance/extended.mjs
node scripts/acceptance/final.mjs
```

smoke 创建独立验收用户，保存 var/smoke-context.json。后续脚本使用该账号；aiops 会导入 SOP、创建 MCP 连接并分析 order-service 开发事故。extended 检查 PDF、Skill、记忆和案例再检索，final 检查依赖与受限文档检索。

结果和认证上下文位于被 Git 忽略的 var 目录。验收需要真实模型调用权限，会消耗模型服务额度。若日志尚不可见，先检查 SLS 索引、查询时间及 incident_id 后再执行诊断。
