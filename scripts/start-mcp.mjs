import { readFileSync, mkdirSync, openSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const base = JSON.parse(readFileSync(new URL('../config/project.json', import.meta.url)));
const user = JSON.parse(readFileSync(new URL('../config/user.project.json', import.meta.url)));
const sls = { ...base.slsMcpServer, ...user.slsMcpServer };
const upload = { ...base.slsLogUpload, ...user.slsLogUpload };
mkdirSync(new URL('../var', import.meta.url), { recursive: true });
const log = openSync(new URL('../var/mcp.log', import.meta.url), 'a');
const hasStaticCredentials =
  Boolean(sls.accessKeyId ?? upload.accessKeyId) && Boolean(sls.accessKeySecret ?? upload.accessKeySecret);
const proxy = hasStaticCredentials
  ? 'uvx alibabacloud-sls-mcp-proxy@latest'
  : `uvx alibabacloud-sls-mcp-proxy@latest --profile ${sls.profile || 'default'}`;
const child = spawn(process.execPath, [
  fileURLToPath(new URL('../node_modules/supergateway/dist/index.js', import.meta.url)),
  '--stdio', proxy,
  '--host', '127.0.0.1',
  '--port', String(sls.port),
  '--outputTransport', 'sse'
], {
  stdio: ['ignore', log, log],
  detached: true,
  env: {
    ...process.env,
    ALIBABA_CLOUD_ACCESS_KEY_ID: sls.accessKeyId ?? upload.accessKeyId,
    ALIBABA_CLOUD_ACCESS_KEY_SECRET: sls.accessKeySecret ?? upload.accessKeySecret
  }
});
child.unref();
console.log(`SLS MCP pid=${child.pid}`);
