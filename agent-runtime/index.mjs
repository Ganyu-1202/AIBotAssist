import { Agent } from '@earendil-works/pi-agent-core';
import { createAssistantMessageEventStream } from '@earendil-works/pi-ai';
import { createInterface } from 'node:readline';

const pending = new Map(); let seq=0, agent, active=false;
const send = value => process.stdout.write(JSON.stringify(value)+'\n');
function rpc(kind, payload) {
  const id=String(++seq); send({kind,id,...payload});
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);agent?.abort();reject(new Error('Bridge timeout: effect unknown; do not retry'));},110000);
    pending.set(id,{resolve:v=>{clearTimeout(timer);resolve(v);},reject});
  });
}
export function toOpenAI(context) {
  const messages=[{role:'system',content:context.systemPrompt}];
  for(const m of context.messages) {
    if(m.role==='user')messages.push({role:'user',content:typeof m.content==='string'?m.content:m.content.filter(x=>x.type==='text').map(x=>x.text).join('\n')});
    else if(m.role==='assistant') {
      const tool_calls=m.content.filter(x=>x.type==='toolCall').map(x=>({id:x.id,type:'function',function:{name:x.name,arguments:JSON.stringify(x.arguments)}}));
      const a={role:'assistant',content:m.content.filter(x=>x.type==='text').map(x=>x.text).join('\n')};
      if(tool_calls.length)a.tool_calls=tool_calls; messages.push(a);
    } else if(m.role==='toolResult')messages.push({role:'tool',tool_call_id:m.toolCallId,content:m.content.filter(x=>x.type==='text').map(x=>x.text).join('\n')});
  }
  return messages;
}
async function run(init) {
  if(active)throw new Error('Already active');active=true;let turns=0, executions=0;
  const model={id:'minecraft-java-bridge',name:'Minecraft',api:'openai-completions',provider:'java',baseUrl:'https://unused.invalid/v1',reasoning:false,input:['text'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0},contextWindow:64000,maxTokens:12000};
  agent=new Agent({initialState:{systemPrompt:init.system,model,thinkingLevel:'off',tools:init.tools.map(t=>({
    name:t.function.name,label:t.function.name,description:t.function.description,parameters:t.function.parameters,
    execute:async(_id,args)=>{
      if(['execute_task','build_structure','repair_structure','craft_items','process_items','run_commands'].includes(t.function.name) && ++executions>8)throw new Error('Execution budget exhausted');
      const result=await rpc('tool',{name:t.function.name,args});
      if(result.status==='unknown') {agent.abort();throw new Error('Unknown effect; stop without retry');}
      return {content:[{type:'text',text:JSON.stringify(result)}],details:result};
    }
  }))},toolExecution:'sequential',shouldStopAfterTurn:()=>++turns>=12,
  streamFn:(_model,context)=>{
    const stream=createAssistantMessageEventStream();
    const usage={input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}};
    const base={role:'assistant',api:model.api,provider:model.provider,model:model.id,timestamp:Date.now(),usage};
    (async()=>{
      try {
        const response=await rpc('model',{messages:toOpenAI(context)});
        const content=[];
        if(response.content)content.push({type:'text',text:response.content});
        for(const c of response.tool_calls??[])content.push({type:'toolCall',id:c.id,name:c.function.name,arguments:JSON.parse(c.function.arguments)});
        const message={...base,content,stopReason:response.tool_calls?.length?'toolUse':'stop'};
        stream.push({type:'done',reason:message.stopReason,message});stream.end(message);
      } catch {
        const error={...base,content:[],stopReason:'error',errorMessage:'Model bridge failed; no automatic retry'};
        stream.push({type:'error',reason:'error',error});stream.end(error);
      }
    })();return stream;
  }});
  agent.subscribe(event=>{
    if(event.type==='message_end'&&event.message.role==='assistant')for(const c of event.message.content)if(c.type==='text'&&c.text)send({kind:'text',text:c.text});
  });
  const deadline=setTimeout(()=>agent.abort(),600000);
  try {await agent.prompt(init.prompt);send({kind:'done',error:agent.state.errorMessage??null});}
  finally {clearTimeout(deadline);active=false;}
}
if(!process.env.AIBOT_PI_TEST_IMPORT) {
  const lines=createInterface({input:process.stdin,crlfDelay:Infinity});
  lines.on('line',line=>{
    try {
      if(line.length>1500000)throw new Error('Oversized bridge message');
      const msg=JSON.parse(line);
      if(msg.kind==='response') {const item=pending.get(msg.id);if(item){pending.delete(msg.id);item.resolve(msg.result);}}
      else if(msg.kind==='start')run(msg).catch(()=>send({kind:'done',error:'Pi runtime failed'}));
      else if(msg.kind==='abort')agent?.abort();
    }catch{send({kind:'done',error:'Invalid bridge message'});}
  });
  lines.on('close',()=>{agent?.abort();process.exit(0);});
  send({kind:'ready',runtime:'pi-agent-core',version:'0.85.1'});
}
