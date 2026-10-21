const assert = require("node:assert/strict");
const { test } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { webcrypto } = require("node:crypto");
const ts = require("typescript");
const axios = require("axios");

function memoryStorage() {
  const values = new Map();
  return {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  };
}

// Execute the shipped TypeScript functions, with storage and the HTTP adapter as the boundaries.
// Axios's real default transforms/headers are retained; no network requests are made here.
function fixture() {
  const window = { sessionStorage: memoryStorage(), localStorage: memoryStorage(), crypto: webcrypto,
    location: { origin: "https://care.example" } };
  const store = { state: { app: { token: "", userPeofile: {} } } };
  const modules = new Map();
  const calls = [];
  const http = {
    defaults: axios.defaults,
    get: (url, config) => { calls.push({ url, config }); return Promise.resolve(); },
    put: (url, data, config) => { calls.push({ url, data, config }); return Promise.resolve(); }
  };
  function load(relative) {
    if (modules.has(relative)) return modules.get(relative);
    const module = { exports: {} };
    modules.set(relative, module.exports);
    const source = fs.readFileSync(path.join(__dirname, "..", "src", relative + ".ts"), "utf8");
    const code = ts.transpileModule(source, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true
    } }).outputText;
    vm.runInNewContext(code, {
      window, localStorage: window.localStorage, module, exports: module.exports,
      require: name => name === "@/utils" ? { http }
        : name === "@/store" ? { default: store, __esModule: true }
        : name === "@/utils/http" ? { default: http, __esModule: true, isSameOriginApi: config => {
          const target = new URL(axios.getUri(config), window.location.origin);
          return target.origin === window.location.origin && /^https?:$/.test(target.protocol) && target.pathname.startsWith("/api/");
        } }
        : name.startsWith("@/") ? load(name.slice(2)) : require(name)
    }, { filename: relative + ".ts" });
    return module.exports;
  }
  function session(staffId, token) {
    store.state.app = { userPeofile: { userid: staffId }, token };
    window.localStorage.setItem("stateData", JSON.stringify({ app: { userPeofile: { userid: staffId }, token } }));
  }
  function dispatch(config, data, headers) {
    const transforms = Array.isArray(config.transformRequest) ? config.transformRequest : [config.transformRequest];
    for (const transform of transforms) data = transform.call(config, data, headers);
    return data;
  }
  return { window, session, dispatch, calls, intent: load("utils/rechargeIntent"), api: load("apis/depositRecharge") };
}

test("decimal input is normalized without rounding unsupported precision or exceeding the database range", () => {
  const { intent } = fixture();
  for (const [input, expected] of [["0.01", "0.01"], ["0.29", "0.29"], ["0010.1", "10.10"],
    ["10", "10.00"], ["99999999.99", "99999999.99"]]) assert.equal(intent.normalizeRechargeAmount(input), expected);
  for (const input of [null, "", "0", "-1", "0.001", "1e2", "100000000", "99999999999999999999"])
    assert.equal(intent.normalizeRechargeAmount(input), null);
});

test("unconfirmed intents survive reload and isolate both account and elder", () => {
  const { intent } = fixture();
  const pending = intent.prepareRechargeIntent("4", "17", "10");
  assert.match(pending.requestId, /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
  assert.equal(intent.prepareRechargeIntent("4", "17", "10.00").requestId, pending.requestId);
  assert.equal(intent.loadRechargeIntent("4", "17").requestId, pending.requestId);
  assert.equal(intent.loadRechargeIntent("1", "17"), null);
  assert.equal(intent.loadRechargeIntent("4", "18"), null);
  assert.notEqual(intent.prepareRechargeIntent("4", "17", "11").requestId, pending.requestId);
  intent.clearRechargeIntent("4", pending);
  assert.equal(intent.loadRechargeIntent("4", "17").amount, "11.00");
});

test("storage failure prevents preparing a request and a mismatched receipt cannot clear the intent", () => {
  const { intent, window } = fixture();
  const pending = intent.prepareRechargeIntent("4", "17", "10");
  const receipt = { ...pending, id: 1, staffId: 4 };
  assert.equal(intent.receiptMatchesIntent(receipt, "4", pending), true);
  for (const change of [{ staffId: 1 }, { elderId: 18 }, { amount: "11.00" }, { requestId: webcrypto.randomUUID() }])
    assert.equal(intent.receiptMatchesIntent({ ...receipt, ...change }, "4", pending), false);
  window.sessionStorage.setItem = () => { throw new Error("fixture storage quota"); };
  assert.throws(() => intent.prepareRechargeIntent("4", "17", "11"), /fixture storage quota/);
  assert.equal(intent.loadRechargeIntent("4", "17").requestId, pending.requestId);
  intent.clearRechargeIntent("4", pending);
  assert.equal(intent.loadRechargeIntent("4", "17"), null);
});

test("both persisted identity and token must match, with missing or corrupt storage denied", () => {
  const { intent, session, window } = fixture();
  const captured = { staffId: "4", token: "session-a" };
  session(4, "session-a");
  assert.equal(intent.matchesPersistedRechargeSession(captured), true);
  session(1, "session-a");
  assert.equal(intent.matchesPersistedRechargeSession(captured), false);
  session(4, "session-b");
  assert.equal(intent.matchesPersistedRechargeSession(captured), false);
  window.localStorage.setItem("stateData", "broken-json");
  assert.equal(intent.matchesPersistedRechargeSession(captured), false);
  window.localStorage.removeItem("stateData");
  assert.equal(intent.matchesPersistedRechargeSession(captured), false);
});

test("the final request transform preserves JSON and binds the immutable captured token", () => {
  const { api, session, dispatch, intent } = fixture();
  session(4, "session-a");
  const captured = { staffId: "4", token: "session-a" };
  const config = api.rechargeSessionConfig(captured);
  // Model the global interceptor selecting another token after the component captured its identity.
  const headers = new axios.AxiosHeaders({ token: "session-b" });
  captured.staffId = "1"; captured.token = "session-b";
  const payload = { elderId: "17", amount: "10.00", requestId: webcrypto.randomUUID() };
  assert.deepEqual(JSON.parse(dispatch(config, payload, headers)), payload);
  assert.equal(headers.get("token"), "session-a");
  session(1, "session-b");
  assert.equal(headers.get("token"), "session-a");
  assert.equal(intent.matchesPersistedRechargeSession({ staffId: "4", token: "session-a" }), false);
});

test("an account switch between the component check and transport transform aborts the request", () => {
  const { api, session, dispatch } = fixture();
  session(4, "session-a");
  const config = api.rechargeSessionConfig({ staffId: "4", token: "session-a" });
  session(1, "session-b");
  assert.throws(() => dispatch(config, { amount: "10.00" }, new axios.AxiosHeaders({ token: "session-b" })), /会话|登录/);
});

test("recharge, receipt reads and ledger pages all enforce the same captured-session transport contract", async () => {
  const { api, session, calls, dispatch } = fixture();
  const captured = { staffId: "4", token: "session-a" };
  session(4, "session-a");
  await api.recharge({ elderId: "17", amount: "10.00", requestId: webcrypto.randomUUID() }, captured);
  await api.pageRechargeLedger({ elderId: "17", pageNum: 1, pageSize: 10 }, captured);
  await api.getRechargeReceipt({ elderId: "17", requestId: webcrypto.randomUUID() }, captured);
  assert.equal(calls.length, 3);
  session(1, "session-b");
  for (const request of calls) assert.throws(() => dispatch(request.config, request.data,
    new axios.AxiosHeaders({ token: "session-b" })), /会话|登录/);
});
