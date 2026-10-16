import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { appendFile, open, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { pipeline } from 'node:stream/promises';

// Authenticated archive format: magic NHB1 + 12-byte nonce + ciphertext + 16-byte GCM tag.
const [mode,input,output,keyPath]=process.argv.slice(2);
if(!['encrypt','decrypt'].includes(mode)||!input||!output||!keyPath)throw new Error('Usage: backup-crypto.mjs encrypt|decrypt input output key-file');
const key=Buffer.from((await readFile(keyPath,'utf8')).trim(),'base64');
if(key.length!==32)throw new Error('Backup key must contain 32 random bytes in Base64');
try{await stat(output);throw new Error('Refusing to overwrite backup output');}catch(e){if(e.code!=='ENOENT')throw e;}
const partial=output+'.'+randomBytes(8).toString('hex')+'.partial';
try{
  if(mode==='encrypt'){
    const nonce=randomBytes(12);const header=Buffer.concat([Buffer.from('NHB1'),nonce]);
    const cipher=createCipheriv('aes-256-gcm',key,nonce);cipher.setAAD(header);
    await writeFile(partial,header,{flag:'wx',mode:0o600});
    await pipeline(createReadStream(input),cipher,createWriteStream(partial,{flags:'a'}));
    await appendFile(partial,cipher.getAuthTag());
  }else{
    const source=await open(input,'r');const length=(await source.stat()).size;
    if(length<32){await source.close();throw new Error('Invalid backup archive');}
    const header=Buffer.alloc(16),tag=Buffer.alloc(16);await source.read(header,0,16,0);await source.read(tag,0,16,length-16);await source.close();
    if(header.subarray(0,4).toString()!=='NHB1')throw new Error('Unsupported backup archive');
    const decipher=createDecipheriv('aes-256-gcm',key,header.subarray(4));decipher.setAAD(header);decipher.setAuthTag(tag);
    await pipeline(createReadStream(input,{start:16,end:length-17}),decipher,createWriteStream(partial,{flags:'wx',mode:0o600}));
  }
  // Consumers only see the final file after GCM authentication completes successfully.
  await rename(partial,output);
}catch(error){await rm(partial,{force:true});throw error;}finally{key.fill(0);}
