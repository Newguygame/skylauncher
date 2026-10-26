const fs=require('fs');
const vm=require('vm');
const code=fs.readFileSync(__dirname+'/../docs/app.js','utf8');
class Element {
 constructor(){this.textContent='';this.href='';this.title='';this.hidden=false;this.children=[];this.attrs={};this.classSet=new Set();this.classList={add:x=>this.classSet.add(x),remove:x=>this.classSet.delete(x)};}
 setAttribute(k,v){this.attrs[k]=v;} removeAttribute(k){delete this.attrs[k]};
 appendChild(x){this.children.push(x)} replaceChildren(...children){this.children=children;this.textContent=''};
 addEventListener(k,v){this[k]=v}; querySelector(){return new Element()}
}
let el={};
const selector=(s)=>el[s]||(el[s]=new Element());
const document={querySelector:selector,createElement:()=>new Element()};
const pref='https://github.com/Newguygame/skylauncher/releases/download/v3.2.1/';
const release={tag_name:'v3.2.1',assets:[
 {name:'SkyLauncherSetup-3.2.1.exe',browser_download_url:pref+'SkyLauncherSetup-3.2.1.exe'},
 {name:'SkyLauncherSetup-3.2.1.dmg',browser_download_url:pref+'SkyLauncherSetup-3.2.1.dmg'},
 {name:'skylauncher-setup_3.2.1_amd64.deb',browser_download_url:pref+'skylauncher-setup_3.2.1_amd64.deb'},
 {name:'SkyLauncher.jar',browser_download_url:pref+'SkyLauncher.jar'}
]};
async function test(data,expected){
 el={};
 const ctx={document,navigator:{platform:'Linux x86_64',userAgent:'Linux'},URL,AbortController,setTimeout,clearTimeout,console,fetch:async()=>({ok:true,json:async()=>data})};
 vm.runInNewContext(code,ctx);
 await new Promise(r=>setTimeout(r,5));
 for(const [os,ext] of Object.entries(expected)) {
  const button=selector(`[data-download="${os}"]`);
  if (ext) {if(!button.href.endsWith(ext)) throw Error(`${os} expected ${ext}, got ${button.href}`)}
  else if(!button.classSet.has('disabled')) throw Error(`${os} should be disabled`);
 }
 return selector('#release-pill').textContent;
}
(async()=>{
 console.log(await test(release,{windows:'.exe',macos:'.dmg',linux:'.deb'}));
 console.log(await test({tag_name:'v3.3.0',assets:[]},{windows:null,macos:null,linux:null}));
 console.log('JS release filtering tests passed');
})().catch(e=>{console.error(e);process.exit(1)});
