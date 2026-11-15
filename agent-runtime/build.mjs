import { spawnSync } from 'node:child_process';
import { mkdirSync,readFileSync,writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
const platforms={ 'windows-x64':'bun-windows-x64','linux-x64':'bun-linux-x64','linux-arm64':'bun-linux-arm64','macos-x64':'bun-darwin-x64','macos-arm64':'bun-darwin-arm64' };
const selected=(process.env.AIBOT_RUNTIME_TARGETS??Object.keys(platforms).join(',')).split(',');
const output=join(import.meta.dir,'../common/build/generated/pi/aibotassist/pi');mkdirSync(output,{recursive:true});
const manifest={version:'pi-0.85.1-bridge-6',files:{}};
for(const key of selected){
 if(!platforms[key])throw new Error('Unsupported target '+key);
 const name='aibotassist-pi-'+key+(key.startsWith('windows')?'.exe':'');
 const file=join(output,name);
 const build=spawnSync(process.execPath,['build',join(import.meta.dir,'index.mjs'),'--compile','--target='+platforms[key],'--no-compile-autoload-dotenv','--no-compile-autoload-bunfig','--outfile',file],{stdio:'inherit',cwd:import.meta.dir});
 if(build.status!==0)throw new Error('Pi build failed '+key);
 const bytes=readFileSync(file);manifest.files[key]={name,sha256:createHash('sha256').update(bytes).digest('hex'),size:bytes.length};
}
writeFileSync(join(output,'manifest.json'),JSON.stringify(manifest));
