# Windows 环境准备

安装 JDK 17+、Maven 3.9+、Git、Node.js 22 LTS、npm 与 Docker Desktop，启用 WSL2 后端并启动 Docker。Java、Maven、Node 和 npm 需要加入 PATH。SLS MCP 需要 uv 和阿里云 CLI（3.3.0 或更高），并执行一次 `aliyun configure --mode OAuth` 完成浏览器授权。

## 检查工具

在命令提示符或 PowerShell 中执行：

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

## 配置与启动

首次使用时从仓库根目录执行；对应文件已存在时跳过复制：

```bat
copy config\project.template.json config\project.json
copy config\user.project.template.json config\user.project.json
```

编辑 user.project.json 填写模型和 SLS 信息。PowerShell 可以使用 Copy-Item 执行相同复制操作。

```bat
.\scripts\start-local.bat
```

启动器通过 Node 管理进程。工作台为 `http://127.0.0.1:5175`，后端为 `http://127.0.0.1:8081`。检查接口时 PowerShell 使用 curl.exe：

```powershell
curl.exe -i http://127.0.0.1:8081/health
curl.exe -i http://127.0.0.1:8081/config/check
```

## 检查与开发数据

Java 和 Node 检查可以原生执行：

```bat
mvn -q test
npm run contracts:typecheck
npm run contracts:test
npm run frontend:typecheck
npm run frontend:test
npm run frontend:build
npm run docs:build
```

`check.sh` 和 `fixtures.sh` 使用 Bash，可在 Git Bash 中执行。使用 PowerShell 运行开发数据命令时，也可直接调用 Java：

```powershell
New-Item -ItemType Directory -Force var | Out-Null
mvn -q -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=var/java-classpath.txt"
$fixtureClasspath = "target/classes;" + (Get-Content var/java-classpath.txt -Raw).Trim()
java -cp $fixtureClasspath io.agentjava.fixtures.FixtureCli generate 30
# 按需将 generate 替换为 upload、alerts 或 seed。
```

开发数据配置和执行顺序见 [日志与告警联调](../tutorials/real-log-and-alert.md)。后台进程查看和停止见 [运维与监控](../operations-and-monitoring.md)。Windows 操作步骤尚未进行实机验收。
