# macOS 环境准备

安装 JDK 17+、Maven 3.9+、Node.js 22 LTS、npm 和 Docker Desktop，启动 Docker 引擎。SLS MCP 需要 uv 和阿里云 CLI（3.3.0 或更高，`brew install uv aliyun-cli`），并执行一次 `aliyun configure --mode OAuth` 完成浏览器授权。确保 Java、Maven、Node 和 npm 位于 PATH；通过 java 与 Maven 的版本输出确认二者使用的 JDK 不低于 17。

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

启动器后台运行 Java、Vite 与 SLS MCP，容器运行向量和告警基础设施。工作台地址为 `http://127.0.0.1:5175`。

配置示例、分步启动和就绪检查见 [运行指南](../getting-started.md)，停止与重启见 [运维与监控](../operations-and-monitoring.md)。
