import { writeFileSync } from 'node:fs';
const base = process.argv[2] ?? 'http://127.0.0.1:8081';
let token = '';
const results=[];
async function api(path,method='GET',body){const headers = token ? {Authorization:`Bearer ${token}`} : {};if(body && !(body instanceof FormData))headers['Content-Type']='application/json';const r=await fetch(base+path,{method,headers,body:body instanceof FormData?body:body?JSON.stringify(body):undefined});const payload=await r.json();if(!r.ok||!payload.ok)throw new Error(`${method} ${path}: ${JSON.stringify(payload)}`);return payload.data;}
function record(name,data){results.push({name,...data});console.log(name,JSON.stringify(data));writeFileSync(new URL('../../var/smoke-results.json',import.meta.url),JSON.stringify(results,null,2));}
const email=`acceptance-${Date.now()}@agent-java.local`;
const auth=await api('/auth/register','POST',{email,displayName:'Java 验收',password:'Acceptance123!'});token=auth.accessToken;record('注册/恢复',{ok:(await api('/auth/me')).id===auth.user.id,email});
const kb=(await api('/knowledge-bases')).items[0].id;
const form=new FormData();form.set('file',new File(['# Java验收知识\n\nAgent Java 的验收标识是 JAVA-AGENTSCOPE-20261004。\n数据库连接池耗尽时检查 HikariCP 活跃连接、等待线程、慢查询与 maxLifetime。'], 'java-acceptance.md',{type:'text/markdown'}));form.set('chunking',JSON.stringify({strategy:'paragraph'}));
const uploaded=await api(`/knowledge-bases/${kb}/documents`,'POST',form);const doc=uploaded.document.id;const task=(await api(`/knowledge-bases/${kb}/documents/${doc}/index-tasks`,'POST')).task;
let indexed;for(let i=0;i<90;i++){indexed=await api(`/knowledge-bases/${kb}/documents/${doc}/index-tasks/${task.id}`);if(['succeeded','failed','cancelled'].includes(indexed.status))break;await new Promise(r=>setTimeout(r,1000));}if(indexed.status!=='succeeded')throw new Error(`index failed ${JSON.stringify(indexed)}`);record('Qwen embedding/Milvus索引',{status:indexed.status,preview:await api(`/knowledge-bases/${kb}/documents/${doc}/chunk-preview`)});
const session=await api('/chat/sessions','POST',{});
async function chat(content){const r=await fetch(`${base}/chat/sessions/${session.id}/messages:stream`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({content})});const text=await r.text();const events=text.split('\n\n').filter(Boolean).map(frame=>JSON.parse(frame.split('\n').find(l=>l.startsWith('data: ')).slice(6)));if(events.some(e=>e.type==='error'))throw new Error(JSON.stringify(events.filter(e=>e.type==='error')));if(events.filter(e=>e.type==='complete').length!==1)throw new Error('missing complete');return events;}
const simple=await chat('请只回复：Java 和 AgentScope 接入成功。');record('真实Qwen普通问答',{events:simple.length,text:simple.filter(e=>e.type==='content.delta').map(e=>e.delta).join('')});
const rag=await chat('请使用 knowledge_retrieval 检索知识库，告诉我 Agent Java 的验收标识，给出来源。');if(!rag.some(e=>e.type==='reference.source'))throw new Error('RAG has no references');record('AgentScope自主RAG/真实rerank',{tools:rag.filter(e=>e.type==='tool.call').map(e=>e.toolCall.name),references:rag.filter(e=>e.type==='reference.source').map(e=>e.reference)});
const time=await chat('请调用 get_current_time 告诉我现在北京时间。');if(!time.some(e=>e.type==='tool.call'))throw new Error('time tool not called');if(time.some(e=>e.type==='reference.source'))throw new Error('cross-turn citation contamination');record('工具/本轮引用隔离',{ok:true});
const detail=await api(`/chat/sessions/${session.id}`);const assistant=detail.messages.filter(m=>m.role==='assistant').at(-1);const fb=await api('/feedback','POST',{targetType:'chat_message',targetId:assistant.id,rating:'positive',comment:'Java验收通过'});record('反馈持久化',{id:fb.id,items:(await api(`/feedback?targetType=chat_message&targetId=${assistant.id}`)).items.length});
writeFileSync(new URL('../../var/smoke-context.json',import.meta.url),JSON.stringify({base,token,userId:auth.user.id,kb,doc,session:session.id}));
record('全部基础真实链路',{ok:true});
