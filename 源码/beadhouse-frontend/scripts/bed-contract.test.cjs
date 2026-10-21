const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const dependencyRequire = process.env.FRONTEND_TEST_DEPENDENCIES
  ? require("node:module").createRequire(path.resolve(process.env.FRONTEND_TEST_DEPENDENCIES,"package.json")) : require;
const ts = dependencyRequire("typescript");
const axios = dependencyRequire("axios");
const vue = dependencyRequire("vue");
const { parse } = dependencyRequire("@vue/compiler-sfc");
const settle = async () => { for (let i=0;i<5;i++) { await vue.nextTick(); await new Promise(resolve=>setImmediate(resolve)); } };
const deferred = () => { let resolve; const promise=new Promise(done=>{resolve=done;}); return {promise,resolve}; };
const ENTER="views/live/enter/enterDialog/index.vue", BOOK="views/sale/book/bookDialog/index.vue";

// Execute the shipped Vue setup and API modules. Vue reactivity and Axios serialization/interceptors
// are real; DOM widgets and the network adapter are the test boundaries.
function fixture() {
  const values=new Map(), listeners=new Map(), modules=new Map(), messages=[];
  const localStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,String(value)),removeItem:key=>values.delete(key)};
  const store={state:vue.reactive({app:{token:"session-a",userPeofile:{userid:4}}}),dispatch() {}};
  const window={location:{origin:"https://care.example"},localStorage,
    addEventListener(name,fn){const set=listeners.get(name)||new Set();set.add(fn);listeners.set(name,set);},
    removeEventListener(name,fn){listeners.get(name)?.delete(fn);}};
  const ui={ElMessage:{error:message=>messages.push(message),success:message=>messages.push(message)}};
  function load(relative) {
    if(relative==="utils/http")relative="utils/http/index";
    if(modules.has(relative))return modules.get(relative);
    const module={exports:{}}; modules.set(relative,module.exports);
    const source=fs.readFileSync(path.join(__dirname,"..","src",relative+".ts"),"utf8");
    vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020,esModuleInterop:true}}).outputText,
      {window,localStorage,URL,Blob,module,exports:module.exports,require:dependency},{filename:relative+".ts"});
    return module.exports;
  }
  function dependency(name) {
    if(name==="@/store")return {default:store,__esModule:true};
    if(name==="element-plus")return ui;
    if(name==="@/utils")return {http:load("utils/http/index").default};
    if(name==="@/utils/formRules"||name==="@element-plus/icons-vue"||name.endsWith(".vue"))return {};
    return name.startsWith("@/")?load(name.slice(2)):dependencyRequire(name);
  }
  function component(relative) {
    const module={exports:{}},hooks=[],emitted=[];
    const file=path.join(__dirname,"..","src",relative);
    const source=parse(fs.readFileSync(file,"utf8")).descriptor.scriptSetup.content;
    const exports=`\nmodule.exports={dialogVisible,${relative.includes("treeDialog")?"treeAcceptParams,checkBed,data":"acceptParams,handleSubmit,formData,drawerProps,ruleFormRef,getCheckBedInfo"}};`.replace(/\\n/g,"\n");
    const scope=vue.effectScope();
    scope.run(()=>vm.runInNewContext(ts.transpileModule(source+exports,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020,esModuleInterop:true}}).outputText,
      {window,localStorage,URL,Blob,module,exports:module.exports,defineExpose(){},defineEmits:()=> (...args)=>emitted.push(args),
        require:name=>name==="vue"?{...vue,onBeforeUnmount:fn=>hooks.push(fn)}:dependency(name)},{filename:file}));
    return {...module.exports,emitted,unmount(){hooks.forEach(fn=>fn());scope.stop();}};
  }
  const http=load("utils/http/index").default, requests=[];
  let reply=config=>({code:200,data:config.url.includes("list")?[]:{}});
  http.defaults.adapter=async config=>{
    requests.push({...config,headers:new axios.AxiosHeaders(config.headers)});
    return {data:await reply(config),status:200,statusText:"fixture",headers:new axios.AxiosHeaders(),config};
  };
  function persist(id,token){localStorage.setItem("stateData",JSON.stringify({app:{token,userPeofile:{userid:id}}}));}
  persist(4,"session-a");
  return {load,component,http,requests,messages,store,persist,reply:fn=>{reply=fn;},storageEvent:()=>listeners.get("storage")?.forEach(fn=>fn())};
}
function params(f,kind,extra={}) {
  const api=kind===ENTER?f.load("apis/checkContract").addCheckContract:f.load("apis/bookManage").addReserve;
  return {title:"新增",isView:false,rowData:{},api,getTableList:async()=>{},...extra};
}

for(const kind of [ENTER,BOOK]) {
  test(`${kind}: opening a new form never requests a detail with missing IDs`,async()=>{
    const f=fixture(); f.reply(config=>{ if(config.url.includes("getReserveByReserveId")||config.url.includes("getCheckContractById")) throw new Error("missing ID detail requested"); return {code:200,data:[]}; });
    const c=f.component(kind); await c.acceptParams(params(f,kind));
    assert.equal(c.dialogVisible.value,true); assert.equal(f.requests.some(r=>r.url.includes("ById")||r.url.includes("ReserveId")),false); c.unmount();
  });
  test(`${kind}: repeated submit is blocked and failed request preserves the form`,async()=>{
    const f=fixture(),c=f.component(kind);await c.acceptParams(params(f,kind));
    c.formData.value.bedId=17;c.formData.value.name="preserved";
    c.ruleFormRef.value={validate:async cb=>{if(cb)cb(true);return true;}};
    const pending=deferred(); f.reply(config=>config.method==="post"?pending.promise:{code:200,data:[]});
    const first=c.handleSubmit(),second=c.handleSubmit();await settle();
    assert.equal(f.requests.filter(r=>r.method==="post").length,1);
    pending.resolve({code:409,msg:"bed occupied"});await Promise.all([first,second]);await settle();
    assert.equal(c.dialogVisible.value,true);assert.equal(c.formData.value.bedId,17);assert.equal(c.formData.value.name,"preserved");c.unmount();
  });
  test(`${kind}: another tab's account change closes old form and blocks submission`,async()=>{
    const f=fixture(),c=f.component(kind);await c.acceptParams(params(f,kind));
    c.ruleFormRef.value={validate:async cb=>{if(cb)cb(true);return true;}};
    f.persist(1,"session-b"); f.storageEvent();await c.handleSubmit();await settle();
    assert.equal(c.dialogVisible.value,false);assert.equal(f.requests.filter(r=>r.method==="post").length,0);c.unmount();
  });
}

test("bed picker uses the caller's authorized tree API",async()=>{
  const f=fixture(),c=f.component("components/treeDialog/index.vue"); let count=0;
  await c.treeAcceptParams({treeApi:async()=>{count++;return {code:200,data:[{id:17,name:"caller tree"}]};}});
  assert.equal(count,1);assert.equal(f.requests.length,0);assert.equal(c.data.value[0].name,"caller tree");c.unmount();
});

test("a late bed response cannot overwrite the newer admission dialog",async()=>{
  const f=fixture(),c=f.component(ENTER),late=deferred();
  f.reply(config=>config.url.includes("getCheckContractById")?{code:200,data:{id:config.params.elderId,bedId:config.params.elderId}}:
    config.url.includes("getBedById")?(config.params.bedId===17?late.promise:{code:200,data:{bedName:"current bed"}}):{code:200,data:[]});
  const first=c.acceptParams(params(f,ENTER,{title:"编辑",rowData:{id:17}}));await settle();
  await c.acceptParams(params(f,ENTER,{title:"编辑",rowData:{id:18}}));
  assert.equal(c.formData.value.bedName,"current bed");
  late.resolve({code:200,data:{bedName:"stale bed"}});await first;
  assert.equal(c.formData.value.id,18);assert.equal(c.formData.value.bedName,"current bed");c.unmount();
});

test("reservation writes bind the captured identity at actual dispatch",async()=>{
  const f=fixture(),session=f.load("utils/authResource").captureResourceSession();
  f.persist(1,"session-b");
  await assert.rejects(f.load("apis/bookManage").addReserve({bedId:17},session));
  await assert.rejects(f.load("apis/bookManage").refund({reserveId:17},session));
  assert.equal(f.requests.length,0);
});
