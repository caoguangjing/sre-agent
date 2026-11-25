import {readFileSync,writeFileSync} from 'node:fs';
const c=JSON.parse(readFileSync(new URL('../../var/smoke-context.json',import.meta.url)));
async function api(path,method='GET',body){const response=await fetch(c.base+path,{method,headers:{Authorization:`Bearer ${c.token}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});const data=await response.json();if(!response.ok||!data.ok){writeFileSync(new URL('../../var/final-failure.json',import.meta.url),JSON.stringify(data,null,2));throw new Error(`${path}: ${response.status} ${data.error?.code}`);}return data.data;}
const ready=await api('/config/check');if(!Object.values(ready.dependencies).every(d=>d.ok))throw new Error('依赖未全部就绪');
const session=await api('/chat/sessions','POST',{title:'最终过滤检索验收'});
const response=await fetch(`${c.base}/chat/sessions/${session.id}/messages:stream`,{method:'POST',headers:{Authorization:`Bearer ${c.token}`,'Content-Type':'application/json'},body:JSON.stringify({content:`请调用 knowledge_retrieval，参数严格为 {"query":"Java验收知识","topK":1,"filters":{"documentIds":["${c.doc}"]}}，告诉我验收标识，并引用来源。`})});
const events=(await response.text()).split('\n\n').filter(Boolean).map(frame=>JSON.parse(frame.split('\n').find(line=>line.startsWith('data: ')).slice(6)));
const refs=events.filter(e=>e.type==='reference.source');if(events.some(e=>e.type==='error')||refs.length!==1||refs[0].reference.documentId!==c.doc||!refs[0].reference.content)throw new Error('受限检索/完整文本结果失败');
const detail=await api(`/chat/sessions/${session.id}`);if(detail.messages.length!==2||!detail.messages[1].content.includes('JAVA-AGENTSCOPE-20261004'))throw new Error('完整持久回答未对账');
const result={ready:true,filteredTopK:true,fullChunkContent:true,persistedAnswer:true,dependencies:ready.dependencies};writeFileSync(new URL('../../var/final-results.json',import.meta.url),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
