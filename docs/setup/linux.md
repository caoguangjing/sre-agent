# Linux 环境准备

安装 JDK 17+、Maven 3.9+、Git、Node.js 22 LTS、npm、Docker Engine 和 Compose v2。SLS MCP 需要 uv 和阿里云 CLI（3.3.0 或更高），并执行一次 `aliyun configure --mode OAuth` 完成浏览器授权。启动 Docker daemon，确保当前用户可以访问 Docker。

## 检查工具

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

## 运行

在仓库根目录创建两份本地 JSON、填写模型和 SLS 配置，然后执行：

```bash
./scripts/start-local.sh
```

Java、Vite 与 SLS MCP 在主机运行，Milvus 等基础设施在容器运行。配置示例、分步启动和就绪检查见 [运行指南](../getting-started.md)。

默认服务监听本机回环地址。开发启动器未配置系统服务或进程守护；需要持续运行时按自己的运行环境管理进程。Linux 操作步骤尚未进行实机验收。
