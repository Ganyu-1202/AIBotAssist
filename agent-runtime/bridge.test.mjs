import { test, expect } from 'bun:test';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

test('actual Pi SDK performs model → tool → tool result → model → completion', async()=>{
  const child=spawn(process.execPath,['index.mjs'],{cwd:import.meta.dir,stdio:['pipe','pipe','pipe']});
  let modelCalls=0, toolCalls=0, text='', errors='';
  child.stderr.on('data',b=>errors+=b.toString());
  const write=m=>child.stdin.write(JSON.stringify(m)+'\n');
  try {
    await new Promise((resolve,reject)=>{
      const timeout=setTimeout(()=>reject(new Error('timeout '+errors)),15000);
      child.on('error',reject);
      createInterface({input:child.stdout}).on('line',line=>{
        try {
          const m=JSON.parse(line);
          if(m.kind==='ready') {
            expect(m.runtime).toBe('pi-agent-core');
            write({kind:'start',prompt:'Observe then summarize',system:'Use the tool and report its result',tools:[{type:'function',function:{name:'observe_world',description:'Observe',parameters:{type:'object',properties:{},additionalProperties:false}}}]});
          } else if(m.kind==='model') {
            modelCalls++;
            if(modelCalls===1)write({kind:'response',id:m.id,result:{role:'assistant',content:null,tool_calls:[{id:'call1',type:'function',function:{name:'observe_world',arguments:'{}'}}]}});
            else {
              expect(m.messages.some(x=>x.role==='tool'&&x.content.includes('observed'))).toBe(true);
              write({kind:'response',id:m.id,result:{role:'assistant',content:'Verified world observation'}});
            }
          }else if(m.kind==='tool') {
            toolCalls++;expect(m.name).toBe('observe_world');write({kind:'response',id:m.id,result:{status:'observed',mode:'creative'}});
          }else if(m.kind==='text')text+=m.text;
          else if(m.kind==='done') {clearTimeout(timeout);expect(m.error).toBeNull();resolve();}
        }catch(e){clearTimeout(timeout);reject(e);}
      });
    });
    expect(modelCalls).toBe(2);expect(toolCalls).toBe(1);expect(text).toContain('Verified world');
  }finally{child.stdin.end();child.kill();}
},20000);

for (const toolName of ['query_recipes','process_items','build_structure','get_task_status']) {
 test(`actual Pi proxies ${toolName} and preserves rejected result`,async()=>{
  const child=spawn(process.execPath,['index.mjs'],{cwd:import.meta.dir,stdio:['pipe','pipe','pipe']});
  const write=m=>child.stdin.write(JSON.stringify(m)+String.fromCharCode(10));let calls=0,toolCalls=0;
  try {await new Promise((resolve,reject)=>{
   const timeout=setTimeout(()=>reject(new Error('Pi test timeout')),15000);
   child.on('error',reject);
   createInterface({input:child.stdout}).on('line',line=>{try{
    const m=JSON.parse(line);
    if(m.kind==='ready')write({kind:'start',prompt:'Test',system:'Use registered tool',tools:[{type:'function',function:{name:toolName,description:'Test Minecraft proxy',parameters:{type:'object',properties:{},additionalProperties:false}}}]});
    else if(m.kind==='model'){
     if(++calls===1)write({kind:'response',id:m.id,result:{role:'assistant',content:null,tool_calls:[{id:'extra1',type:'function',function:{name:toolName,arguments:'{}'}}]}});
     else {expect(m.messages.some(x=>x.role==='tool'&&x.content.includes('missing_material'))).toBe(true);write({kind:'response',id:m.id,result:{role:'assistant',content:'Stopped: missing material'}});}
    }else if(m.kind==='tool'){toolCalls++;expect(m.name).toBe(toolName);write({kind:'response',id:m.id,result:{status:'rejected',reason:'missing_material'}});}
    else if(m.kind==='done'){clearTimeout(timeout);expect(m.error).toBeNull();resolve();}
   }catch(e){clearTimeout(timeout);reject(e);}});
  });expect(calls).toBe(2);expect(toolCalls).toBe(1);}finally{child.stdin.end();child.kill();}
 },20000);
}
