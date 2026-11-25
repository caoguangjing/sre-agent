import {readFileSync,existsSync,mkdirSync,openSync,writeFileSync} from 'node:fs';
import {spawn,spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import net from 'node:net';
const root=fileURLToPath(new URL('..',import.meta.url));
const base=JSON.parse(readFileSync(new URL('../config/project.json',import.meta.url))),user=JSON.parse(readFileSync(new URL('../config/user.project.json',import.meta.url)));
const backend={...base.backend,...user.backend};mkdirSync(new URL('../var',import.meta.url),{recursive:true});
async function listening(port){return new Promise(resolve=>{const s=net.connect(port,'127.0.0.1',()=>{s.end();resolve(true)});s.on('error',()=>resolve(false));s.setTimeout(1000,()=>{s.destroy();resolve(false)});});}
function run(cmd,args){const result=spawnSync(cmd,args,{cwd:root,stdio:'inherit',shell:process.platform==='win32'});if(result.status!==0)process.exit(result.status??1);}
function start(name,cmd,args){const log=openSync(new URL(`../var/${name}.log`,import.meta.url),'a');const child=spawn(cmd,args,{cwd:root,stdio:['ignore',log,log],detached:true,shell:process.platform==='win32'});child.unref();writeFileSync(new URL(`../var/${name}.pid`,import.meta.url),String(child.pid));}
if(!existsSync(new URL('../node_modules',import.meta.url)))run('npm',['ci']);
if(!await listening(19530))run('docker',['compose','-f','infra/compose.yaml','up','-d','--wait','etcd','minio','milvus','attu']);
if(!await listening(9093))run('docker',['compose','-f','infra/compose.yaml','up','-d','--wait','alertmanager']);
const sls={...base.slsMcpServer,...user.slsMcpServer};if(!await listening(sls.port))run('node',['scripts/start-mcp.mjs']);
if(!await listening(backend.port)){run(process.platform==='win32'?'mvn.cmd':'mvn',['-q','-DskipTests','package']);start('backend','java',['-jar','target/agent-java-0.1.0.jar']);}
if(!await listening(5175))start('frontend',process.platform==='win32'?'npm.cmd':'npm',['--workspace','apps/frontend','run','dev','--','--host','127.0.0.1','--port','5175','--strictPort']);
for(const port of [backend.port,5175]){let ready=false;for(let i=0;i<60;i++){if(await listening(port)){ready=true;break;}await new Promise(resolve=>setTimeout(resolve,1000));}if(!ready)throw new Error(`端口${port}未就绪，请查看var日志`);}
console.log(`Java API: http://127.0.0.1:${backend.port}; 工作台: http://127.0.0.1:5175`);
