import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes} from 'node:crypto';
import {mkdtemp,readFile,writeFile,rm,stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve,basename,sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';

test('backup encryption round-trip, tamper rejection and overwrite protection',async()=>{
  const directory=await mkdtemp(join(tmpdir(),'notifyhub-backup-'));
  const script=fileURLToPath(new URL('./backup-crypto.mjs',import.meta.url));
  const run=(mode,input,output,key)=>execFileSync(process.execPath,[script,mode,input,output,key],{stdio:'pipe',timeout:15000});
  try{
    const data=randomBytes(1024*1024),key=join(directory,'key'),input=join(directory,'input'),archive=join(directory,'archive'),output=join(directory,'output');
    await writeFile(key,randomBytes(32).toString('base64'));await writeFile(input,data);
    run('encrypt',input,archive,key);run('decrypt',archive,output,key);assert.deepEqual(await readFile(output),data);
    assert.throws(()=>run('decrypt',archive,output,key));assert.deepEqual(await readFile(output),data);
    const corrupted=await readFile(archive);corrupted[corrupted.length-1]^=1;await writeFile(archive,corrupted);
    const rejected=join(directory,'rejected');assert.throws(()=>run('decrypt',archive,rejected,key));await assert.rejects(stat(rejected));
  }finally{
    const target=resolve(directory);assert.ok(target.startsWith(resolve(tmpdir())+sep)&&basename(target).startsWith('notifyhub-backup-'));
    await rm(target,{recursive:true,force:true});
  }
});
