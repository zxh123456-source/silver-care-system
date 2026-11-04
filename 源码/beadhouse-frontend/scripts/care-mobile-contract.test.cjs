const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript'), vue = require('vue'), axios = require('axios');
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc');
const root = path.resolve(__dirname, '..');
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const settle = async () => { for (let i = 0; i < 4; i++) { await vue.nextTick(); await new Promise(done => setImmediate(done)); } };

// Execute production setup, reactive state, API modules and Axios transforms.
// Only widgets, browser services and the HTTP adapter are boundaries.
function fixture() {
  const modules = new Map(), listeners = new Map(), storage = new Map(), hooks = [], requests = [], messages = [];
  const route = vue.reactive({ fullPath: '/ai-care/workbench' });
  const mediaListeners = new Set(), media = { matches: true, addEventListener: (_, fn) => mediaListeners.add(fn), removeEventListener: (_, fn) => mediaListeners.delete(fn) };
  const store = { state: vue.reactive({ app: { token: 'session-a', userPeofile: { userid: 4 } } }), dispatch: async () => {} };
  const localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)), removeItem: key => storage.delete(key) };
  const window = { location: { origin: 'https://care.example' }, localStorage, matchMedia: () => media,
    addEventListener(name, fn) { const set = listeners.get(name) || new Set(); set.add(fn); listeners.set(name, set); },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); } };
  const session = (id, token) => localStorage.setItem('stateData', JSON.stringify({ app: { token, userPeofile: { userid: id } } }));
  session(4, 'session-a');
  const message = value => messages.push(value);
  for (const name of ['error', 'warning', 'success', 'info']) message[name] = value => messages.push({ type: name, value });
  let prompt = async () => { throw 'cancel'; };
  const lifecycleVue = { ...vue, onMounted() {}, onActivated() {}, onUnmounted: fn => hooks.push(fn), onBeforeUnmount: fn => hooks.push(fn) };
  function dependency(name) {
    if (name === 'vue') return lifecycleVue;
    if (name === 'vue-router') return { useRoute: () => route, useRouter: () => ({ push() {} }) };
    if (name === '@/store') return { __esModule: true, default: store };
    if (name === 'element-plus') return { ElMessage: message, ElMessageBox: { prompt: (...args) => prompt(...args), confirm: async () => true } };
    if (name === '@/utils') return { http: load('utils/http/index').default };
    if (name === '@/utils/careNotifications') return { useCareNotifications: () => ({ refresh: async () => {} }) };
    if (name.endsWith('.vue')) return {};
    if (name === '@/router') return { __esModule: true, default: { push() {}, replace() {} } };
    return name.startsWith('@/') ? load(name.slice(2)) : require(name);
  }
  function execute(source, filename, module) {
    const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true } }).outputText;
    vm.runInNewContext(code, { module, exports: module.exports, require: dependency, window, localStorage,
      URL, Blob, FormData, File, setTimeout, clearTimeout, console, defineProps: () => ({}), defineEmits: () => () => {} }, { filename });
  }
  function load(relative) {
    if (relative === 'utils/http') relative = 'utils/http/index';
    if (modules.has(relative)) return modules.get(relative);
    const module = { exports: {} }, filename = path.join(root, 'src', relative + '.ts');
    modules.set(relative, module.exports); execute(fs.readFileSync(filename, 'utf8'), filename, module); modules.set(relative, module.exports);
    return module.exports;
  }
  const http = load('utils/http/index').default;
  let reply = () => ({ code: 200, data: {} });
  http.defaults.adapter = async config => {
    requests.push({ ...config, headers: new axios.AxiosHeaders(config.headers) });
    return { data: await reply(config), status: 200, statusText: 'fixture', headers: new axios.AxiosHeaders(), config };
  };
  const scope = vue.effectScope();
  return { load, store, requests, messages, media, mediaListeners, route,
    reply: fn => { reply = fn; }, prompt: fn => { prompt = fn; }, persist: session,
    event: name => listeners.get(name)?.forEach(fn => fn()),
    component(page, exposed) { const filename = path.join(root, 'src/views/ai', page, 'index.vue'), module = { exports: {} };
      scope.run(() => execute(parse(fs.readFileSync(filename, 'utf8')).descriptor.scriptSetup.content + '\nmodule.exports={' + exposed + '};', filename, module)); return module.exports;
    },
    navigation() { return scope.run(() => load('layout/useMobileNavigation').useMobileNavigation()); },
    stop() { hooks.forEach(fn => fn()); scope.stop(); }
  };
}

test('mobile navigation closes on route selection and viewport changes, and releases its listener', async () => {
  const f = fixture(), c = f.navigation();
  assert.equal(c.isMobile.value, true); assert.equal(c.navigationOpen.value, false);
  c.navigationOpen.value = true; f.route.fullPath = '/ai-care/health'; await vue.nextTick(); assert.equal(c.navigationOpen.value, false);
  c.navigationOpen.value = true; f.media.matches = false; f.mediaListeners.forEach(fn => fn());
  assert.equal(c.isMobile.value, false); assert.equal(c.navigationOpen.value, false);
  f.stop(); assert.equal(f.mediaListeners.size, 0);
});

test('care save serializes taps, preserves manual corrections on failure and retries exact fields', async () => {
  const f = fixture(), c = f.component('care', 'form,draft,confirmSave,saveError,saveLoading'), pending = deferred();
  c.form.value = { elderId: 7, sourceText: 'original' }; c.draft.value = { elderId: 7, sourceText: 'original', observation: '人工更正', followUp: '继续观察' };
  f.reply(config => config.url.endsWith('/save') ? pending.promise : { code: 200, data: { notes: [] } });
  try {
    const first = c.confirmSave(), second = c.confirmSave(); await settle();
    assert.equal(f.requests.filter(row => row.url.endsWith('/save')).length, 1);
    pending.resolve({ code: 503, msg: 'temporary failure' }); await Promise.all([first, second]);
    assert.equal(c.saveLoading.value, false); assert.match(c.saveError.value, /草稿已保留/);
    assert.equal(c.draft.value.observation, '人工更正'); assert.equal(c.form.value.sourceText, 'original');
    assert.equal(f.messages.some(row => row.type === 'success'), false);
    f.reply(() => ({ code: 200, data: { notes: [] } })); await c.confirmSave();
    const writes = f.requests.filter(row => row.url.endsWith('/save'));
    assert.equal(writes.length, 2); assert.equal(writes[1].data, writes[0].data);
    assert.equal(c.draft.value, null); assert.equal(c.form.value.sourceText, '');
  } finally { f.stop(); }
});

test('health and medication failed forms preserve entered values and block duplicate writes', async () => {
  for (const [page, submit, field, errorField, endpoint, value] of [
    ['health', 'saveMeasurement', 'form', 'saveError', '/measurement', { elderId: 7, temperature: 36.8, remarks: '保留测量备注' }],
    ['medication', 'savePlan', 'plan', 'saveError', '/plan', { elderId: 7, medicineName: '医嘱药品', doseInstruction: '执行说明', periods: ['早'], endDate: '' }]
  ]) {
    const f = fixture(), pending = deferred(), c = f.component(page, `${submit},${field},${errorField},saving`); c[field].value = value;
    f.reply(() => pending.promise);
    try {
      const first = c[submit](), duplicate = c[submit](); await settle(); assert.equal(f.requests.length, 1);
      pending.resolve({ code: 503, msg: 'try again' }); await Promise.all([first, duplicate]);
      assert.equal(c.saving.value, false); assert.match(c[errorField].value, /输入已保留/);
      assert.deepEqual(JSON.parse(JSON.stringify(c[field].value)), value); assert.equal(f.messages.some(row => row.type === 'success'), false);
      f.reply(config => ({ code: 200, data: config.url.endsWith('/trend') ? { records: [] } : { tasks: [] } }));
      await c[submit](); assert.equal(f.requests.filter(row => row.url.endsWith(endpoint)).length, 2);
    } finally { f.stop(); }
  }
});

test('care read failures expose retry and stale handover responses cannot overwrite newer dates', async () => {
  const f = fixture(), c = f.component('care', 'loadHandover,handover,handoverError,handoverLoading'), pending = deferred();
  f.reply(() => ({ code: 503, msg: 'unavailable' }));
  try {
    await c.loadHandover(); assert.match(c.handoverError.value, /重新加载/); assert.equal(c.handoverLoading.value, false);
    f.reply(() => pending.promise); const old = c.loadHandover(); await settle();
    f.reply(() => ({ code: 200, data: { date: 'new' } })); await c.loadHandover();
    pending.resolve({ code: 200, data: { date: 'old' } }); await old;
    assert.equal(c.handover.value.date, 'new'); assert.equal(c.handoverError.value, '');
  } finally { f.stop(); }
});

test('care account changes clear resident data and ignore delayed draft responses', async () => {
  const f = fixture(), c = f.component('care', 'form,draft,makeDraft,selectedElderName'), pending = deferred();
  c.form.value = { elderId: 7, sourceText: 'private observation' }; c.selectedElderName.value = 'previous resident'; f.reply(() => pending.promise);
  try {
    const running = c.makeDraft(); await settle(); f.persist(9, 'session-b'); f.event('storage');
    assert.equal(c.form.value.elderId, 0); assert.equal(c.selectedElderName.value, '');
    pending.resolve({ code: 200, data: { elderId: 7, sourceText: 'previous account' } }); await running;
    assert.equal(c.draft.value, null);
  } finally { f.stop(); }
});

test('medication skipped reason survives failed write and cannot cross an account switch while prompting', async () => {
  const f = fixture(), c = f.component('medication', 'mark,actionError,actionBusy'), row = { planId: 7, period: '早' };
  f.prompt(async () => ({ value: '人工核对原因' })); f.reply(() => ({ code: 503, msg: 'offline' }));
  try {
    await c.mark(row, 'SKIPPED'); assert.match(c.actionError.value, /原因会保留/); assert.equal(c.actionBusy.value, false);
    f.prompt(async (_text, _title, options) => { assert.equal(options.inputValue, '人工核对原因'); f.persist(9, 'session-b'); f.event('storage'); return { value: 'late note' }; });
    await c.mark(row, 'SKIPPED'); assert.equal(f.requests.length, 1);
  } finally { f.stop(); }
});

test('daily completion retains failed notes and prevents duplicate write taps', async () => {
  const f = fixture(), c = f.component('daily', 'completeTask,taskError,taskBusy'), pending = deferred(), row = { id: 7, revision: 1 };
  f.prompt(async () => ({ value: '人工复核内容' })); f.reply(() => pending.promise);
  try {
    const first = c.completeTask(row), second = c.completeTask(row); await settle(); assert.equal(f.requests.length, 1);
    pending.resolve({ code: 503, msg: 'offline' }); await Promise.all([first, second]);
    assert.match(c.taskError.value, /复核结果会保留/); assert.equal(c.taskBusy.value, false);
    f.prompt(async (_text, _title, options) => { assert.equal(options.inputValue, '人工复核内容'); throw 'cancel'; });
    await c.completeTask(row); assert.equal(f.requests.length, 1);
  } finally { f.stop(); }
});

test('all care writes reject a captured session changed before entry or at final HTTP dispatch', async () => {
  for (const [api, method, args] of [
    ['aiCare', 'saveCareNote', [{ elderId: 7, sourceText: 'old' }]], ['aiCare', 'updateCareNote', [{ id: 3, elderId: 7, sourceText: 'old' }]],
    ['aiHealth', 'addHealthMeasurement', [{ elderId: 7, temperature: 36.7 }]], ['aiMedication', 'addMedicationPlan', [{ elderId: 7 }]],
    ['aiMedication', 'recordMedicationExecution', [{ planId: 3 }]], ['aiDaily', 'actDailyTask', ['complete', { id: 3, revision: 1 }]],
    ['aiDaily', 'actCareAlert', ['resolve', { id: 3, revision: 1 }]]
  ]) {
    const f = fixture(), session = f.load('utils/authResource').captureResourceSession(); f.persist(9, 'session-b');
    assert.throws(() => f.load('apis/' + api)[method](...args, session)); assert.equal(f.requests.length, 0); f.stop();
    const g = fixture(), captured = g.load('utils/authResource').captureResourceSession();
    g.load('utils/http/index').default.interceptors.request.use(config => { g.persist(9, 'session-b'); return config; });
    await assert.rejects(g.load('apis/' + api)[method](...args, captured)); assert.equal(g.requests.length, 0); g.stop();
  }
});

test('the four responsive page templates and navigation compile with production Vue', () => {
  for (const file of ['views/ai/care/index.vue', 'views/ai/health/index.vue', 'views/ai/daily/index.vue', 'views/ai/medication/index.vue', 'layout/index.vue', 'layout/components/NavBar/index.vue']) {
    const filename = path.join(root, 'src', file), { descriptor, errors } = parse(fs.readFileSync(filename, 'utf8'));
    assert.deepEqual(errors, []); const script = compileScript(descriptor, { id: file });
    const result = compileTemplate({ source: descriptor.template.content, filename, id: file, compilerOptions: { bindingMetadata: script.bindings } });
    assert.deepEqual(result.errors, []);
  }
});
