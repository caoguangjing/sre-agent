import {readFileSync,writeFileSync} from 'node:fs';
const {base,token}=JSON.parse(readFileSync(new URL('../../var/smoke-context.json',import.meta.url)));
const sunfireToken=process.env.SUNFIRE_MCP_TOKEN;
if(!sunfireToken)throw new Error('缺少环境变量 SUNFIRE_MCP_TOKEN（mcp.alibaba-inc.com 个人令牌）');
const headers={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
async function api(path,method='GET',body){const r=await fetch(base+path,{method,headers,body:body?JSON.stringify(body):undefined});const p=await r.json();if(!r.ok||!p.ok)throw new Error(`${path}: HTTP ${r.status}`);return p.data;}
const connection=await api('/mcp/connections','POST',{name:'Sunfire验收',transport:'streamable_http',url:'https://mcp.alibaba-inc.com/sunfire-mcp/mcp',enabled:true,timeoutSeconds:20,retries:1,headers:{Authorization:`Bearer ${sunfireToken}`}});
try {
 const checked=await api(`/mcp/connections/${connection.id}:check`,'POST');
 if(!checked.connection.lastCheck.ok)throw new Error(`sunfire连接检查失败：${checked.connection.lastCheck.error}`);
 const names=checked.tools.map(t=>t.name);
 for(const required of ['queryAlarmList','queryAppErrorLog','queryTraceList','queryMetricsByPromQL'])
  if(!names.includes(required))throw new Error(`sunfire工具发现缺少 ${required}`);
 if(checked.connection.headers?.Authorization===`Bearer ${sunfireToken}`)throw new Error('header值未脱敏，接口返回了真实令牌');
 writeFileSync(new URL('../../var/sunfire-mcp-results.json',import.meta.url),JSON.stringify({connectionCheck:true,toolCount:checked.tools.length,headersMasked:true},null,2));
 console.log(JSON.stringify({connectionCheck:true,toolCount:checked.tools.length,headersMasked:true}));
} finally {await api(`/mcp/connections/${connection.id}`,'DELETE');}
