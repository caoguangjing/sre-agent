import {build} from 'esbuild';
import {writeFileSync} from 'node:fs';
const result=await build({entryPoints:['packages/api-contracts/src/openapi.ts'],bundle:true,write:false,format:'esm',platform:'node'});
const {OPENAPI_CONTRACT}=await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
writeFileSync(new URL('../src/main/resources/openapi.json',import.meta.url),JSON.stringify(OPENAPI_CONTRACT,null,2));
console.log('OpenAPI共享契约导出成功');
