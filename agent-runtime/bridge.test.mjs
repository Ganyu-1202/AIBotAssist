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

for (const toolName of ['query_recipes','process_items','build_structure','get_task_status','query_jei','read_xaero_map','world_snapshot','inspect_machine','machine_blueprint','run_advanced_command','read_client_diagnostics','fakeplayer_control']) {
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


for(const captureName of ['capture_view','capture_map','capture_fakeplayer']) {
test(`actual Pi passes ${captureName} images, preserves tool ordering and newest image`,async()=>{
 const child=spawn(process.execPath,['index.mjs'],{cwd:import.meta.dir,stdio:['pipe','pipe','pipe']});
 const write=m=>child.stdin.write(JSON.stringify(m)+'\n');
 const image1=Buffer.from([255,216,1,255,217]).toString('base64');
 const image2=Buffer.from([255,216,2,255,217]).toString('base64');
 let calls=0,captures=0;
 const call=(id,name)=>({id,type:'function',function:{name,arguments:'{}'}});
 try {await new Promise((resolve,reject)=>{
  const timeout=setTimeout(()=>reject(new Error('View bridge timeout')),15000);
  child.on('error',reject);
  createInterface({input:child.stdout}).on('line',line=>{try{
   const m=JSON.parse(line);
   if(m.kind==='ready')write({kind:'start',prompt:'Look at my screen',system:'Use view tool',tools:[captureName,'observe_world'].map(name=>({type:'function',function:{name,description:'Test',parameters:{type:'object',properties:{},additionalProperties:false}}}))});
   else if(m.kind==='model'){
    calls++;
    if(calls===1)write({kind:'response',id:m.id,result:{role:'assistant',content:null,tool_calls:[call('view1',captureName),call('world1','observe_world')]}});
    else {
     const images=m.messages.flatMap(x=>Array.isArray(x.content)?x.content.filter(p=>p.type==='image_url'):[]);
     expect(images).toHaveLength(1);
     expect(images[0].image_url.url).toBe('data:image/jpeg;base64,'+(calls===2?image1:image2));
     const tools=m.messages.filter(x=>x.role==='tool');
     expect(tools.every(x=>typeof x.content==='string'&&!x.content.includes('base64')&&!x.content.includes(image1)&&!x.content.includes(image2))).toBe(true);
     expect(m.messages.at(-1).role).toBe('user');
     expect(m.messages.at(-2).role).toBe('tool');
     if(calls===2){
      expect(tools.map(x=>x.tool_call_id)).toEqual(['view1','world1']);
      write({kind:'response',id:m.id,result:{role:'assistant',content:null,tool_calls:[call('view2',captureName)]}});
     }else write({kind:'response',id:m.id,result:{role:'assistant',content:'Image received'}});
    }
   }else if(m.kind==='tool'){
    const result=m.name===captureName?{status:'captured',width:1,height:1,captured_at:++captures,image:{mimeType:'image/jpeg',data:captures===1?image1:image2}}:{status:'observed'};
    write({kind:'response',id:m.id,result});
   }else if(m.kind==='done'){clearTimeout(timeout);expect(m.error).toBeNull();resolve();}
  }catch(e){clearTimeout(timeout);reject(e);}});
 });expect(calls).toBe(3);expect(captures).toBe(2);}finally{child.stdin.end();child.kill();}
},20000);
}



for(const scenario of ['correct-after-review','capture-fails','disabled']) {
 test(`visual calibration: ${scenario}`,async()=>{
  const child=spawn(process.execPath,['index.mjs'],{cwd:import.meta.dir,stdio:['pipe','pipe','pipe']});
  const write=m=>child.stdin.write(JSON.stringify(m)+'\n');let modelCalls=0,captures=0,builds=0,text='';
  const image=Buffer.from([255,216,1,255,217]).toString('base64');
  const tool=(id,name)=>({role:'assistant',content:null,tool_calls:[{id,type:'function',function:{name,arguments:'{}'}}]});
  try {await new Promise((resolve,reject)=>{
   const timeout=setTimeout(()=>reject(new Error('Calibration timeout')),15000);child.on('error',reject);
   createInterface({input:child.stdout}).on('line',line=>{try{
    const m=JSON.parse(line);
    if(m.kind==='ready')write({kind:'start',prompt:'Build and inspect',system:'Test',language:'zh_cn',visual_review:scenario!=='disabled',tools:['build_structure','repair_structure','capture_view'].map(name=>({type:'function',function:{name,description:'Test',parameters:{type:'object',properties:{},additionalProperties:false}}}))});
    else if(m.kind==='model'){
     modelCalls++;
     let result;
     if(modelCalls===1)result=tool('build','build_structure');
     else if(scenario==='correct-after-review'&&modelCalls===3){
      expect(m.messages.at(-1).content.some(p=>p.type==='image_url')).toBe(true);result=tool('repair','repair_structure');
     }else if(scenario==='correct-after-review'&&modelCalls===5)result={role:'assistant',content:'Reviewed final result'};
     else result={role:'assistant',content:'Premature completion'};
     write({kind:'response',id:m.id,result});
    }else if(m.kind==='tool'){
     let result={status:'completed'};
     if(m.name==='capture_view'){
      captures++;
      result=scenario==='capture-fails'?{status:'rejected',reason:'Window not rendering'}:{status:'captured',width:1,height:1,image:{mimeType:'image/jpeg',data:image}};
     }else builds++;
     write({kind:'response',id:m.id,result});
    }else if(m.kind==='text')text+=m.text;
    else if(m.kind==='done'){clearTimeout(timeout);expect(m.error).toBeNull();resolve();}
   }catch(e){clearTimeout(timeout);reject(e);}});
  });
  if(scenario==='correct-after-review'){expect(captures).toBe(2);expect(builds).toBe(2);expect(modelCalls).toBe(5);expect(text).toBe('Reviewed final result');}
  if(scenario==='capture-fails'){expect(captures).toBe(1);expect(modelCalls).toBe(3);expect(text).toContain('图片校准未完成');}
  if(scenario==='disabled'){expect(captures).toBe(0);expect(modelCalls).toBe(2);expect(text).toBe('Premature completion');}
  }finally{child.stdin.end();child.kill();}
 },20000);
}
