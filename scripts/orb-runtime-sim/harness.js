// Minimal DOM stub to run speaking-orb.js + orb.html script logic headlessly
const fs = require('fs'), path = require('path');
const root = process.env.JARVIS_ROOT || require('path').join(__dirname, '..', '..');
let NOW = 0;
const mkStyle = () => new Proxy({}, { set(t,k,v){t[k]=v;return true}, get(t,k){ if(k==='setProperty') return (a,b)=>{t[a]=b}; return t[k]; }});
class El {
  constructor(tag){ this.tag=tag; this.children=[]; this._text=''; this.style=mkStyle(); this.dataset={}; this.classList={add(){},remove(){},toggle(){}}; this.attrs={}; this.parentNode=null; this.width=0; this.height=0; this.listeners={}; }
  get childElementCount(){return this.children.length}
  get textContent(){ return this.children.length? this.children.map(c=>c.textContent).join('') : this._text }
  set textContent(v){ this.children=[]; this._text=String(v) }
  append(c){ c.parentNode=this; this.children.push(c) }
  getAttribute(k){ return k in this.attrs? this.attrs[k]: null }
  setAttribute(k,v){ const old=this.attrs[k]; this.attrs[k]=String(v); const obs=this.constructor.observedAttributes||[]; if(this.attributeChangedCallback && obs.includes(k) && old!==String(v)) this.attributeChangedCallback(k) }
  hasAttribute(k){ return k in this.attrs }
  addEventListener(){} removeEventListener(){} dispatchEvent(){}
  getBoundingClientRect(){ return {left:0,top:0,width:420,height:600} }
  getContext(){ const c = new Proxy({}, {get:(t,k)=>{ if(k==='measureText') return ()=>({width:30,fontBoundingBoxAscent:20,fontBoundingBoxDescent:6}); if(k==='getImageData') return ()=>({data:new Uint8ClampedArray(4*200).fill(255)}); if(k==='createRadialGradient') return ()=>({addColorStop(){}}); if(k==='canvas') return this; return t[k] ?? (()=>{})}, set:(t,k,v)=>{t[k]=v;return true}}); return c; }
  get clientWidth(){return 420}
}
const shadow = { set innerHTML(v){ this._h=v; this._canv=[new El('canvas'),new El('canvas')]; this._cap=new El('div'); this._fx=new El('canvas'); this._orb=new El('div'); this._canv.forEach(c=>c.parentNode=this._orb); },
  querySelectorAll(){ return this._canv }, querySelector(s){ return s==='.cap'? this._cap : this._fx } };
class HTMLElement extends El { constructor(){ super('x') } attachShadow(){ this.shadowRoot = shadow; return shadow } }
const reg = {};
global.HTMLElement = HTMLElement;
global.customElements = { get:n=>reg[n], define:(n,c)=>{reg[n]=c} };
global.matchMedia = q => ({ matches:false, addEventListener(){}, removeEventListener(){} });
global.ResizeObserver = class { constructor(f){this.f=f} observe(){} disconnect(){} };
global.devicePixelRatio = 1;
global.navigator = { userAgent:'Android' };
let rafQ = [];
global.requestAnimationFrame = f => { rafQ.push(f); return rafQ.length };
global.cancelAnimationFrame = ()=>{};
global.CustomEvent = class {};
const realPerf = performance;
global.performance = { now: () => NOW };
global.getComputedStyle = () => ({ fontWeight:'500', fontSize:'28px', fontFamily:'serif' });
delete global.speechSynthesis;
const docEls = {};
global.document = { createElement: t=>new El(t), getElementById: id => docEls[id], addEventListener(){}, visibilityState:'visible' };
global.window = global; global.window.addEventListener = ()=>{}; window.matchMedia = global.matchMedia;
global.getComputedStyle = global.getComputedStyle;
const timers = [];
global.setTimeout = (f,ms)=>{ timers.push({f,at:NOW+ms}); return timers.length };
window.setTimeout = global.setTimeout; window.clearTimeout = ()=>{};
// load speaking-orb.js
eval(fs.readFileSync(path.join(root,'app/src/main/assets/speaking-orb.js'),'utf8'));
const orb = new (reg['speaking-orb'])();
orb.getBoundingClientRect = ()=>({left:0,top:0,width:420,height:600});
orb.setAttribute('state','done'); orb.setAttribute('rest','listening');
orb.canvas = null;
orb.connectedCallback();
docEls['jarvisOrb']=orb; docEls['orbRoot']=new El('div');
// extract inline script of orb.html
const html = fs.readFileSync(path.join(root,'app/src/main/assets/orb.html'),'utf8');
const m = html.match(/<script>\s*\(\(\) => \{[\s\S]*?\}\)\(\);\s*<\/script>/);
const code = m[0].replace(/^<script>/,'').replace(/<\/script>$/,'');
eval(code);
function step(ms){ // advance clock + frames
  const end = NOW+ms;
  while(NOW<end){ NOW+=33; const q=rafQ; rafQ=[]; q.forEach(f=>f(NOW)); for(const t of timers.slice()){ if(t.at<=NOW && !t.done){ t.done=true; t.f(); } } }
}
module.exports = { orb, step, J: window.JarvisOrb, now:()=>NOW, El };
