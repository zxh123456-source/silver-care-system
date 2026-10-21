const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const ts = require("typescript");
const axios = require("axios");
const vue = require("vue");
const { parse } = require("@vue/compiler-sfc");

function fixture() {
  const values = new Map();
  const localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  };
  const store = { state: vue.reactive({ app: { token: "", userPeofile: {} } }), dispatches: [],
    dispatch(action) { this.dispatches.push(action); } };
  const messages = [];
  const released = [];
  let created = 0;
  class ResourceURL extends URL {
    static createObjectURL() { return `blob:fixture-${++created}`; }
    static revokeObjectURL(value) { released.push(value); }
  }
  const listeners = new Map();
  const window = { location: { origin: "https://care.example" }, localStorage,
    addEventListener(name, handler) { const handlers = listeners.get(name) || new Set(); handlers.add(handler); listeners.set(name, handlers); },
    removeEventListener(name, handler) { listeners.get(name)?.delete(handler); } };
  const modules = new Map();
  function load(relative) {
    if (relative === "utils/http") relative = "utils/http/index";
    if (modules.has(relative)) return modules.get(relative);
    const module = { exports: {} };
    modules.set(relative, module.exports);
    const source = fs.readFileSync(path.join(__dirname, "..", "src", relative + ".ts"), "utf8");
    const code = ts.transpileModule(source, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true
    } }).outputText;
    vm.runInNewContext(code, {
      window, localStorage, URL: ResourceURL, Blob, FormData, File, module, exports: module.exports,
      require: name => name === "@/store" ? { default: store, __esModule: true }
        : name === "element-plus" ? { ElMessage: { error: message => messages.push(message) } }
        : name === "@/utils" ? { http: load("utils/http/index").default }
        : name.startsWith("@/") ? load(name.slice(2)) : require(name)
    }, { filename: relative + ".ts" });
    return module.exports;
  }
  function uploadComponent(initialImages = [], uploadType = "single") {
    const props = vue.reactive({ uploadParams: { imageList: initialImages, uploadType } });
    const emitted = [], unmountHooks = [];
    const module = { exports: {} };
    const file = path.join(__dirname, "..", "src/components/upload/image/index.vue");
    const source = parse(fs.readFileSync(file, "utf8")).descriptor.scriptSetup.content;
    const code = ts.transpileModule(source + `
      module.exports = { imageList, uploading, sessionInvalid, uploadBefore, uploadRequest,
        handleError, handleSuccess, handleRemove, handlePreview, dialogVisible, dialogImageUrl };`,
      { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true } }).outputText;
    const scope = vue.effectScope();
    scope.run(() => vm.runInNewContext(code, {
      window, localStorage, URL: ResourceURL, Blob, FormData, File, module, exports: module.exports,
      defineProps: () => props, defineEmits: () => (...args) => emitted.push(args),
      require: name => name === "@/store" ? { default: store, __esModule: true }
        : name === "vue" ? { ...vue, onBeforeUnmount: fn => unmountHooks.push(fn) }
        : name === "element-plus" ? { ElMessage: { error: message => messages.push(message) } }
        : name === "@element-plus/icons-vue" ? {}
        : name.startsWith("@/") ? load(name.slice(2)) : require(name)
    }, { filename: file }));
    return { ...module.exports, props, emitted, unmount() { unmountHooks.forEach(fn => fn()); scope.stop(); } };
  }
  const http = load("utils/http/index").default;
  const requests = [];
  let reply = () => ({ data: { code: 200, data: {} }, status: 200 });
  http.defaults.adapter = async config => {
    requests.push({ ...config, headers: new axios.AxiosHeaders(config.headers) });
    const response = { statusText: "fixture", headers: new axios.AxiosHeaders(), config, ...await reply(config) };
    if (response.status >= 400) throw new axios.AxiosError("fixture rejection", "ERR_BAD_REQUEST", config, {}, response);
    return response;
  };
  function persist(staffId, token) {
    localStorage.setItem("stateData", JSON.stringify({ app: { token, userPeofile: { userid: staffId } } }));
  }
  function login(staffId, token) {
    persist(staffId, token);
    store.state.app = { token, userPeofile: { userid: staffId } };
  }
  login(4, "session-a");
  return { load, http, requests, store, messages, released, login, persist, uploadComponent,
    storageEvent: () => listeners.get("storage")?.forEach(handler => handler()),
    reply: handler => { reply = handler; }, created: () => created };
}

test("HTTP sends tokens only to same-origin API paths and strips caller-supplied tokens elsewhere", async () => {
  const f = fixture();
  for (const url of ["/api/file/1/content", "https://care.example/api/elderRecord/exportExcel"])
    await f.http.get(url);
  for (const url of ["https://external.example/api/file/1/content", "//external.example/api/file/1/content", "/upload/old.png"])
    await f.http.get(url, { headers: { Token: "caller-secret" } });
  assert.deepEqual(f.requests.map(request => request.headers.get("token")), ["session-a", "session-a", undefined, undefined, undefined]);
});

test("binary JSON authentication errors reject downloads and preserve 401 versus 403 semantics", async () => {
  const denied = fixture();
  denied.reply(() => ({ status: 200, data: new Blob([JSON.stringify({ code: 403, msg: "fixture denied" })], { type: "application/json" }) }));
  await assert.rejects(denied.http.get("/api/elderRecord/exportExcel", { responseType: "blob" }));
  assert.equal(denied.store.dispatches.length, 0);
  const expired = fixture();
  expired.reply(() => ({ status: 401, data: new Blob([JSON.stringify({ code: 401, msg: "fixture expired" })], { type: "application/json" }) }));
  await assert.rejects(expired.http.get("/api/file/1/content", { responseType: "blob" }));
  assert.deepEqual(expired.store.dispatches, ["app/logout"]);
});

test("a late binary 401 from an old token never logs out a replacement account", async () => {
  const f = fixture();
  f.reply(config => {
    assert.equal(config.headers.get("token"), "session-a");
    f.persist(1, "session-b");
    return { status: 401, data: new Blob([JSON.stringify({ code: 401 })], { type: "application/json" }) };
  });
  await assert.rejects(f.http.get("/api/file/1/content", { responseType: "blob" }));
  assert.equal(f.store.dispatches.length, 0);
});

test("resource resolution never fetches legacy external URLs and validates returned canonical paths", async () => {
  const f = fixture();
  const resource = f.load("utils/authResource");
  const session = resource.captureResourceSession();
  f.reply(config => config.url === "/api/file/resolve"
    ? { status: 200, data: { code: 200, data: { id: 17, url: "/api/file/17/content" } } }
    : { status: 200, data: new Blob(["fixture image"], { type: "image/png" }) });
  const result = await resource.fetchResourceImage("https://legacy.example/upload/old.png", session);
  assert.deepEqual(f.requests.map(request => request.url), ["/api/file/resolve", "/api/file/17/content"]);
  assert.equal(result.resourceUrl, "/api/file/17/content");
  assert.match(result.previewUrl, /^blob:/);
  result.release(); result.release();
  assert.deepEqual(f.released, [result.previewUrl]);
  assert.equal(resource.canonicalResourceUrl("https://external.example/api/file/17/content"), null);
  assert.equal(resource.canonicalResourceUrl("/api/file/17/content?token=secret"), null);
  assert.equal(resource.canonicalResourceUrl("blob:stale"), null);
});

test("unknown legacy references and non-image responses cannot create image previews", async () => {
  const f = fixture();
  const resource = f.load("utils/authResource");
  const session = resource.captureResourceSession();
  f.reply(() => ({ status: 403, data: { code: 403 } }));
  await assert.rejects(resource.fetchResourceImage("https://unknown.example/image.png", session));
  assert.deepEqual(f.requests.map(request => request.url), ["/api/file/resolve"]);
  f.reply(() => ({ status: 200, data: new Blob(["<svg></svg>"], { type: "image/svg+xml" }) }));
  await assert.rejects(resource.fetchResourceImage("/api/file/17/content", session));
  assert.equal(f.created(), 0);
});

test("a resolver reply cannot redirect authenticated image reads to another origin or resource", async () => {
  for (const data of [{ id: 17, url: "https://external.example/api/file/17/content" },
    { id: 18, url: "/api/file/17/content" }, { id: 17, url: "data:image/png;base64,unknown" }]) {
    const f = fixture();
    const resource = f.load("utils/authResource");
    f.reply(() => ({ status: 200, data: { code: 200, data } }));
    await assert.rejects(resource.fetchResourceImage("https://legacy.example/upload/old.png", resource.captureResourceSession()));
    assert.equal(f.requests.length, 1);
    assert.equal(f.created(), 0);
  }
});

test("resource transport binds a copied session after shared interceptors and aborts changed accounts", () => {
  const f = fixture();
  const resource = f.load("utils/authResource");
  const session = resource.captureResourceSession();
  const config = { ...resource.resourceSessionConfig(session), url: "/api/file/17/content" };
  session.staffId = "1"; session.token = "session-b";
  const headers = new axios.AxiosHeaders({ token: "session-b" });
  let data = { fixture: "payload" };
  for (const transform of config.transformRequest) data = transform.call(config, data, headers);
  assert.equal(JSON.parse(data).fixture, "payload");
  assert.equal(headers.get("token"), "session-a");
  f.persist(1, "session-b");
  assert.throws(() => config.transformRequest.at(-1).call(config, data, headers));
  assert.equal(headers.get("token"), "session-a");
});

test("image response from a replaced session cannot create a preview or accept new-session credentials", async () => {
  const f = fixture();
  const resource = f.load("utils/authResource");
  const session = resource.captureResourceSession();
  f.reply(config => {
    assert.equal(config.headers.get("token"), "session-a");
    f.persist(1, "session-b");
    return { status: 200, data: new Blob(["fixture image"], { type: "image/png" }) };
  });
  await assert.rejects(resource.fetchResourceImage("/api/file/17/content", session));
  assert.equal(f.created(), 0);
  await assert.rejects(resource.fetchResourceImage("/api/file/17/content", session));
  assert.equal(f.requests.length, 1);
});

test("export validation accepts an XLSX ZIP blob and rejects JSON or an HTML error page", async () => {
  const f = fixture();
  const resource = f.load("utils/authResource");
  const workbook = new Blob([new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2])], { type: resource.XLSX_MIME });
  assert.equal(await resource.requireWorkbookBlob(workbook), workbook);
  for (const value of [new Blob(['{"code":403}'], { type: "application/json" }),
    new Blob(["<html>error</html>"], { type: resource.XLSX_MIME }), { code: 500, msg: "fixture error" }])
    await assert.rejects(resource.requireWorkbookBlob(value));
});


const settle = async () => { for (let i = 0; i < 4; i++) { await vue.nextTick(); await new Promise(resolve => setImmediate(resolve)); } };

test("failed image replacement keeps the old preview and persisted resource unchanged", async () => {
  const f = fixture();
  f.reply(() => ({ status: 200, data: new Blob(["image"], { type: "image/png" }) }));
  const component = f.uploadComponent([{ name: "old.png", url: "/api/file/17/content" }]);
  await settle();
  const oldPreview = component.imageList.value[0].url;
  assert.match(oldPreview, /^blob:/);
  f.reply(() => ({ status: 500, data: { code: 500, msg: "fixture upload failure" } }));
  const raw = new File(["png"], "new.png", { type: "image/png" });
  const newFile = { uid: 99, name: raw.name, url: "blob:local-file", status: "ready" };
  component.imageList.value.push(newFile);
  assert.equal(component.uploadBefore(raw), true);
  await assert.rejects(component.uploadRequest({ file: raw }));
  component.handleError(new Error("upload failed"), newFile);
  assert.equal(component.imageList.value.length, 1);
  assert.equal(component.imageList.value[0].url, oldPreview);
  assert.equal(component.emitted.length, 0);
  assert.equal(f.released.includes(oldPreview), false);
  component.unmount();
  assert.equal(f.released.includes(oldPreview), true);
});

test("successful image replacement emits canonical URL and releases both old and local previews", async () => {
  const f = fixture();
  f.reply(config => config.url === "/api/file/uploadImg"
    ? { status: 200, data: { code: 200, data: { id: 18, url: "/api/file/18/content" } } }
    : { status: 200, data: new Blob(["image"], { type: "image/png" }) });
  const component = f.uploadComponent([{ name: "old.png", url: "/api/file/17/content" }]);
  await settle();
  const oldPreview = component.imageList.value[0].url;
  const raw = new File(["png"], "new.png", { type: "image/png" });
  component.imageList.value.push({ uid: 99, name: raw.name, url: "blob:local-file", status: "ready" });
  component.uploadBefore(raw);
  const result = await component.uploadRequest({ file: raw });
  const newFile = component.imageList.value.find(file => file.uid === 99);
  component.handleSuccess(result, newFile);
  assert.deepEqual(component.emitted, [["setImageData", "/api/file/18/content"]]);
  assert.equal(component.imageList.value.length, 1);
  assert.equal(component.imageList.value[0].url, result.image.previewUrl);
  assert.ok(f.released.includes(oldPreview));
  assert.ok(f.released.includes("blob:local-file"));
  assert.equal(f.requests.find(request => request.url === "/api/file/uploadImg").headers.get("token"), "session-a");
  assert.ok(f.requests.find(request => request.url === "/api/file/uploadImg").data instanceof FormData);
  component.unmount();
  assert.ok(f.released.includes(result.image.previewUrl));
});

test("unresolved legacy image remains in form output when another image is removed", async () => {
  const f = fixture();
  f.reply(config => config.url === "/api/file/resolve" ? { status: 404, data: { code: 404 } }
    : { status: 200, data: new Blob(["image"], { type: "image/png" }) });
  const legacy = "https://unknown.example/original.png";
  const component = f.uploadComponent([{ name: "legacy", url: legacy }, { name: "owned", url: "/api/file/17/content" }], "multiple");
  await settle();
  assert.equal(component.imageList.value[0].url, undefined);
  assert.equal(component.emitted.length, 0);
  component.handleRemove(component.imageList.value[1]);
  assert.equal(JSON.stringify(component.emitted), JSON.stringify([["setImageData", [legacy]]]));
  assert.equal(f.requests.some(request => request.url === legacy), false);
  component.unmount();
});

test("cross-tab account change removes previews and late responses cannot redisplay old images", async () => {
  const f = fixture();
  let finish;
  f.reply(config => config.url === "/api/file/18/content" ? new Promise(resolve => { finish = resolve; })
    : { status: 200, data: new Blob(["image"], { type: "image/png" }) });
  const component = f.uploadComponent([{ name: "first", url: "/api/file/17/content" }, { name: "pending", url: "/api/file/18/content" }]);
  await settle();
  const firstPreview = component.imageList.value[0].url;
  component.handlePreview(component.imageList.value[0]);
  assert.equal(component.dialogVisible.value, true);
  f.persist(1, "session-b"); f.storageEvent();
  assert.equal(component.imageList.value.length, 0);
  assert.equal(component.dialogVisible.value, false);
  assert.equal(component.sessionInvalid.value, true);
  assert.ok(f.released.includes(firstPreview));
  finish({ status: 200, data: new Blob(["late image"], { type: "image/png" }) });
  await settle();
  assert.equal(component.imageList.value.length, 0);
  assert.equal(f.created(), 1);
  assert.equal(component.emitted.length, 0);
  component.unmount();
});

test("export API validates workbook bytes and rejects a late successful response after an account switch", async () => {
  const f = fixture();
  const resource = f.load("utils/authResource");
  const api = f.load("apis/elderRecord");
  const session = resource.captureResourceSession();
  const workbook = new Blob([new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2])], { type: resource.XLSX_MIME });
  f.reply(() => ({ status: 200, data: workbook }));
  assert.equal(await api.exportExcel(session), workbook);
  assert.equal(f.requests[0].responseType, "blob");
  f.reply(() => { f.persist(1, "session-b"); return { status: 200, data: workbook }; });
  await assert.rejects(api.exportExcel(session));
});
