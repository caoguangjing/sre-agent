import {readFileSync,writeFileSync} from 'node:fs';
const {base,token}=JSON.parse(readFileSync(new URL('../../var/smoke-context.json',import.meta.url)));
const project=JSON.parse(readFileSync(new URL('../../config/project.json',import.meta.url))),user=JSON.parse(readFileSync(new URL('../../config/user.project.json',import.meta.url)));
const headers={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
async function api(path,method='GET',body){const r=await fetch(base+path,{method,headers,body:body?JSON.stringify(body):undefined});const p=await r.json();if(!r.ok||!p.ok)throw new Error(`${path}: HTTP ${r.status}`);return p.data;}
const connection=await api('/mcp/connections','POST',{name:'官方SLS验收',transport:'sse',url:user.mcp?.slsSseUrl??project.mcp.slsSseUrl,enabled:true,timeoutSeconds:20,retries:1});
try {
 const checked=await api(`/mcp/connections/${connection.id}:check`,'POST');if(!checked.connection.lastCheck.ok||!checked.tools.some(t=>t.name==='sls_list_projects'))throw new Error('official discovery missing tool');
 const session=await api('/chat/sessions','POST',{});
 const stream=await fetch(`${base}/chat/sessions/${session.id}/messages:stream`,{method:'POST',headers,body:JSON.stringify({content:'请使用 MCP 工具 sls_list_projects，列出我可以访问的 SLS Project，并回答返回了多少个 Project。'})});
 const frames=(await stream.text()).split('\n\n').filter(Boolean).map(f=>JSON.parse(f.split('\n').find(l=>l.startsWith('data: ')).slice(6)));
 if(frames.some(e=>e.type==='error')||!frames.some(e=>e.type==='tool.call'&&e.toolCall.name==='sls_list_projects'&&e.toolCall.status==='completed'))throw new Error('AgentScope real MCP invocation failed');
 const answer=frames.filter(e=>e.type==='content.delta').map(e=>e.delta).join('');if(!answer.trim())throw new Error('MCP project list answer mismatch');
 writeFileSync(new URL('../../var/mcp-results.json',import.meta.url),JSON.stringify({connectionCheck:true,toolCount:checked.tools.length,reactMcp:true,answer},null,2));
 console.log(JSON.stringify({connectionCheck:true,toolCount:checked.tools.length,reactMcp:true}));
} finally {await api(`/mcp/connections/${connection.id}`,'DELETE');}
