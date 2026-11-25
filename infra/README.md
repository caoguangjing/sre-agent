# 本地容器基础设施

`infra/compose.yaml` 管理 etcd、MinIO、Milvus、Attu 和 Alertmanager。Java 后端、Vite 前端与官方 SLS MCP 在主机运行。

## 启动与状态

在仓库根目录执行：

```bash
docker compose -f infra/compose.yaml up -d --wait etcd minio milvus attu alertmanager
docker compose -f infra/compose.yaml ps
docker compose -f infra/compose.yaml logs --tail 100 milvus
```

已有 Milvus 时，只启动需要的服务，例如：

```bash
docker compose -f infra/compose.yaml up -d --wait alertmanager
```

| 服务 | 主机端口 / 地址 |
|---|---|
| Milvus SDK/gRPC | `localhost:19530` |
| Milvus 健康检查 | `http://localhost:9091/healthz` |
| MinIO API / 控制台 | `http://localhost:9000` / `http://localhost:9001` |
| Attu | `http://localhost:8001` |
| Alertmanager | `http://localhost:9093` |

etcd 不映射主机端口。Milvus 使用 etcd 和 MinIO；四个数据卷由 Compose 管理。镜像、健康检查、端口和卷以 compose.yaml 为准，JSON 中的 docker 字段不会改变此文件。

## 应用服务

配置完成后，可通过根目录的 `scripts/start-local.sh` 或 Windows 的 `scripts/start-local.bat` 启动服务。启动器按本机 19530、9093 监听情况决定是否启动对应容器。具体步骤、地址及日志见 [运行指南](../docs/getting-started.md)。

已有端口监听时，应核对服务与配置是否一致。多个应用使用同一个 Milvus 时，按需要配置不同的 collectionName。

## 停止与数据

```bash
docker compose -f infra/compose.yaml down
```

普通停止保留数据卷。Java 默认 SQLite 数据库位于 `var/agent-java.db`，不在容器中。开发数据上传与告警发布通过显式命令执行，见 [日志与告警联调](../docs/tutorials/real-log-and-alert.md)。
