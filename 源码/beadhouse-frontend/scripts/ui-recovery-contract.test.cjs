const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const dependencyRequire = process.env.FRONTEND_TEST_DEPENDENCIES
  ? require('node:module').createRequire(path.resolve(process.env.FRONTEND_TEST_DEPENDENCIES, 'package.json')) : require;
const ts = dependencyRequire('typescript');
const axios = dependencyRequire('axios');
const vue = dependencyRequire('vue');
const { parse } = dependencyRequire('@vue/compiler-sfc');
const settle = async () => { for (let i = 0; i < 5; i++) { await vue.nextTick(); await new Promise(done => setImmediate(done)); } };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const TREND = 'views/home/trend.vue', AVATAR = 'layout/components/NavBar/components/Avatar.vue';
const EDITOR = 'views/people/old/oldDialog/index.vue', SELECTOR = 'components/elderListDialog/index.vue';

// Run the shipped TypeScript, Vue setup, reactivity and Axios transform pipeline.
// DOM widgets, chart rendering and the HTTP adapter are the boundaries.
function fixture() {
  const values = new Map(), listeners = new Map(), modules = new Map(), overrides = new Map();
  const messages = [], dispatches = [], requests = [], charts = [], logs = [], validationErrors = [];
  const localStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
  const store = { state: vue.reactive({ app: { token: 'session-a', userPeofile: { userid: 4, username: 'fixture', avator: '' } } }),
    dispatch(action) { dispatches.push(action); return Promise.resolve(); } };
  const window = { location: { origin: 'https://care.example' }, localStorage, onresize() {},
    addEventListener(name, fn) { const set = listeners.get(name) || new Set(); set.add(fn); listeners.set(name, set); },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); } };
  const message = value => messages.push(value);
  for (const name of ['error', 'success', 'warning', 'info']) message[name] = message;
  let prompt = async () => { throw new Error('Unexpected prompt'); };
  let active;
  const lifecycleVue = { ...vue,
    onMounted(fn) { active.mounted.push(fn); },
    onActivated(fn) { active.activated.push(fn); },
    onBeforeUnmount(fn) { active.unmounted.push(fn); },
    onUnmounted(fn) { active.unmounted.push(fn); } };
  const echarts = { init(element) {
    assert.ok(element, 'chart must never initialize on an unmounted DOM ref');
    const chart = { options: [], resizes: 0, disposals: 0,
      setOption(value) { this.options.push(value); }, resize() { this.resizes++; }, dispose() { this.disposals++; } };
    charts.push(chart); return chart;
  } };
  function dependency(name) {
    if (overrides.has(name)) return overrides.get(name);
    if (name === 'vue') return lifecycleVue;
    if (name === 'echarts') return echarts;
    if (name === '@/store') return { default: store, __esModule: true };
    if (name === 'element-plus') return { ElMessage: message, ElMessageBox: { confirm: async () => true, prompt: (...args) => prompt(...args) } };
    if (name === '@/utils') return { http: load('utils/http/index').default };
    if (name === '@/hooks/useIcons') return { useRenderIcon() {} };
    if (name === '@/utils/formRules' || name === '@element-plus/icons-vue' || name.endsWith('.vue')) return {};
    if (name === '@/router') return { default: { push() {}, replace() {} }, __esModule: true };
    if (name === 'vue-router') return { useRouter: () => ({ push() {}, replace() {} }) };
    return name.startsWith('@/') ? load(name.slice(2)) : dependencyRequire(name);
  }
  function execute(source, file, module, extra = {}) {
    const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true } }).outputText;
    vm.runInNewContext(code, { window, localStorage, URL, Blob, FormData, File, module, exports: module.exports,
      require: dependency, console: { log: (...args) => logs.push(args), error: (...args) => logs.push(args), warn: (...args) => logs.push(args) },
      setTimeout, clearTimeout, TextEncoder, ...extra }, { filename: file });
  }
  function load(relative) {
    if (relative === 'utils/http') relative = 'utils/http/index';
    if (modules.has(relative)) return modules.get(relative);
    const module = { exports: {} }; modules.set(relative, module.exports);
    const file = path.join(__dirname, '..', 'src', relative + '.ts');
    execute(fs.readFileSync(file, 'utf8'), file, module);
    return module.exports;
  }
  function lifecycle(run) {
    const previous = active, hooks = { mounted: [], activated: [], unmounted: [] }, scope = vue.effectScope(); active = hooks;
    let value;
    try { value = scope.run(run); } finally { active = previous; }
    let disposed = false;
    return { ...value, async mount() { await Promise.all(hooks.mounted.map(fn => fn())); }, unmount() {
      if (!disposed) { disposed = true; hooks.unmounted.forEach(fn => fn()); scope.stop(); }
    } };
  }
  function component(relative, exposed) {
    return lifecycle(() => {
      const file = path.join(__dirname, '..', 'src', relative), module = { exports: {} }, emitted = [];
      const source = parse(fs.readFileSync(file, 'utf8')).descriptor.scriptSetup.content;
      const exported = exposed || (relative === TREND ? 'Echarts' : relative === AVATAR
        ? 'editPassVisible,editPassShow,handleEditPass,formData,loading,ruleFormRef'
        : relative === EDITOR ? 'acceptParams,handleSubmit,dialogVisible,formData,ruleFormRef,drawerProps'
          : 'elderAcceptParams,getTableList,checkElder,dialogVisible');
      execute(source + '\nmodule.exports={' + exported + '};', file, module,
        { defineExpose() {}, defineEmits: () => (...args) => emitted.push(args) });
      return { ...module.exports, emitted };
    });
  }
  const http = load('utils/http/index').default;
  let reply = config => ({ code: 200, data: config.url.includes('businessTrend') ? [] : {} });
  http.defaults.adapter = async config => {
    requests.push({ ...config, headers: new axios.AxiosHeaders(config.headers) });
    return { data: await reply(config), status: 200, statusText: 'fixture', headers: new axios.AxiosHeaders(), config };
  };
  function persist(id, token) { localStorage.setItem('stateData', JSON.stringify({ app: { token, userPeofile: { userid: id } } })); }
  persist(4, 'session-a');
  function validForm() { return { validate(callback) {
    if (!callback) return Promise.resolve(true);
    return Promise.resolve(callback(true)).catch(error => { validationErrors.push(error); return false; });
  } }; }
  return { load, component, http, store, requests, charts, dispatches, messages, logs, validationErrors, validForm, window, listeners,
    persist, event: name => listeners.get(name)?.forEach(fn => fn()), reply: fn => { reply = fn; },
    stub: (name, value) => overrides.set(name, value), prompt: fn => { prompt = fn; },
    table: api => lifecycle(() => load('hooks/useTable').useTable(api)) };
}

for (const [parentFile, pickerFile, handler, select, event, listField, idField] of [
  ['views/diet/package/packageDialog/index.vue', 'views/diet/package/packageDialog/dishesListDialog.vue',
    'addDishes', 'operateDishes', 'addDishes', 'setDishesVoList', 'dishesIdList'],
  ['views/diet/order/orderDialog/index.vue', 'views/diet/package/packageDialog/dishesListDialog.vue',
    'addDishes', 'operateDishes', 'addDishes', 'setDishesVoList', null],
  ['views/serve/level/levelDialog/index.vue', 'views/serve/level/levelDialog/serviceListDialog.vue',
    'addService', 'operateService', 'addService', 'nurseGradeServiceVoList', 'serviceIdList']
]) {
  test(`selection events carry arrays through the parent form: ${parentFile}`, async () => {
    const f = fixture(), parent = f.component(parentFile, handler + ',formData');
    const picker = f.component(pickerFile, select + ',confirmData,acceptParams,dialogVisible');
    try {
      await picker.acceptParams();
      picker[select]([{ id: 7, dishesName: 'first', typeName: 'a', price: 10 },
        { id: 7, dishesName: 'duplicate', typeName: 'a', price: 10 },
        { id: 8, dishesName: 'second', typeName: 'b', price: 12, orderNum: 2 }]);
      picker.confirmData();
      assert.equal(picker.dialogVisible.value, false);
      assert.equal(picker.emitted.length, 1);
      const [name, selected] = picker.emitted[0];
      assert.equal(name, event);
      assert.ok(Array.isArray(selected), 'Component events must carry selected rows, not a Vue ref');
      parent[handler](selected);
      const rows = parent.formData.value[listField];
      assert.deepEqual(Array.from(rows, row => row.id), [7, 8], 'Repeated selections must be deduplicated by ID');
      assert.equal(rows[0].dishesName, 'first');
      if (idField) assert.deepEqual(Array.from(parent.formData.value[idField]), [7, 8]);
      else {
        assert.equal(rows[0].orderNum, 1, 'A newly selected dish defaults to one portion');
        assert.equal(rows[1].orderNum, 2, 'An explicit portion count must be preserved');
      }
      assert.equal(f.requests.length, 0, 'Local selection must not dispatch a write');
    } finally { picker.unmount(); parent.unmount(); }
  });
}

for (const [handler, endpoint, busy] of [
  ['resolveAlert', '/api/ai/daily/alerts/resolve', 'alertBusy'],
  ['completeTask', '/api/ai/daily/tasks/complete', 'taskBusy']
]) {
  test(`daily prompt cancellation skips writes and confirmation retains the note: ${handler}`, async () => {
    const f = fixture();
    // Polling is exercised by the notification contract; keep this test focused on the prompt/write boundary.
    f.stub('@/utils/careNotifications', { useCareNotifications: () => ({ refresh: async () => {} }) });
    const c = f.component('views/ai/daily/index.vue', handler + ',' + busy);
    const row = { id: 7, revision: 3 }, note = ' reviewed note ';
    try {
      f.prompt(async () => { throw new Error('User canceled the prompt'); });
      await c[handler](row);
      assert.equal(f.requests.length, 0);
      assert.equal(c[busy].value, false);
      f.prompt(async (text, title, options) => {
        assert.notEqual(options.inputValidator(''), true);
        assert.notEqual(options.inputValidator('a'.repeat(501)), true);
        assert.equal(options.inputValidator('a'.repeat(500)), true);
        return { value: note };
      });
      f.reply(config => ({ code: 200, data: config.url.endsWith('/overview') ? {} : [] }));
      await c[handler](row);
      const writes = f.requests.filter(request => request.method === 'post');
      assert.equal(writes.length, 1);
      assert.equal(writes[0].url, endpoint);
      assert.deepEqual(JSON.parse(writes[0].data), { id: 7, revision: 3, note });
      assert.equal(c[busy].value, false);
    } finally { c.unmount(); }
  });
}

test('request tickets reject older requests, closed dialogs and mutated captured identity', () => {
  const f = fixture(); let current = 'a';
  const scope = f.load('utils/asyncScope').createAsyncScope(session => session.token === current);
  const original = { token: 'a', staffId: '4' }, first = scope.begin(original);
  original.token = 'mutated';
  assert.equal(first.session.token, 'a');
  const second = scope.begin({ token: 'a', staffId: '4' });
  assert.equal(scope.isCurrent(first), false); assert.equal(scope.isCurrent(second), true);
  scope.invalidate(); assert.equal(scope.isCurrent(second), false);
  const third = scope.begin({ token: 'a', staffId: '4' });
  current = 'b'; assert.equal(scope.isCurrent(third), false);
  assert.throws(() => scope.begin({ token: 'a', staffId: '4' }));
  scope.dispose(); assert.throws(() => scope.begin({ token: 'b', staffId: '1' }));
});

test('trend ignores a delayed response after unmount without touching a null DOM ref', async () => {
  const f = fixture(), pending = deferred(); f.reply(() => pending.promise);
  const c = f.component(TREND); c.Echarts.value = { fixture: true };
  const mounting = c.mount(); await settle(); c.unmount(); c.Echarts.value = null;
  pending.resolve({ code: 200, data: [{ month: '1月', consultNum: 3 }] });
  await mounting; assert.equal(f.charts.length, 0);
});

test('trend disposes its own chart and resize listener without replacing other handlers', async () => {
  const f = fixture(), previous = f.window.onresize, c = f.component(TREND); c.Echarts.value = { fixture: true };
  await c.mount(); assert.equal(f.charts.length, 1); assert.equal(f.window.onresize, previous);
  f.event('resize'); assert.equal(f.charts[0].resizes, 1);
  c.unmount(); assert.equal(f.charts[0].disposals, 1);
  f.event('resize'); assert.equal(f.charts[0].resizes, 1);
});

test('table keeps the newest page when older HTTP results arrive later', async () => {
  const f = fixture(), first = deferred(), second = deferred(); let calls = 0;
  const c = f.table(() => ++calls === 1 ? first.promise : second.promise);
  const older = c.getTableList(); c.pageable.value.pageNum = 2; const newer = c.getTableList();
  second.resolve({ code: 200, data: { list: [{ id: 2 }], pageNum: 2, pageSize: 10, total: 20 } }); await newer;
  first.resolve({ code: 200, data: { list: [{ id: 1 }], pageNum: 1, pageSize: 10, total: 10 } }); await older;
  assert.equal(c.tableData.value[0].id, 2); assert.equal(c.pageable.value.pageNum, 2); assert.equal(c.pageable.value.total, 20); c.unmount();
});

test('table discards an in-flight response after unmount or another tab changes identity', async () => {
  for (const action of ['unmount', 'storage']) {
    const f = fixture(), pending = deferred(), c = f.table(() => pending.promise), loading = c.getTableList();
    if (action === 'unmount') c.unmount(); else { f.persist(1, 'session-b'); f.event('storage'); }
    pending.resolve({ code: 200, data: { list: [{ id: 17 }], pageNum: 1, pageSize: 10, total: 1 } }); await loading;
    assert.equal(c.tableData.value.length, 0); assert.equal(c.pageable.value.total, 0); c.unmount();
  }
});

test('password submission sends exact form values and succeeds with local logout only', async () => {
  const f = fixture(), c = f.component(AVATAR);
  assert.equal(c.formData.value.oldPass, ''); assert.equal(c.formData.value.newPass, ''); assert.equal(c.formData.value.confirmPassword, '');
  c.editPassShow(); c.formData.value.oldPass = ' OldPass77 '; c.formData.value.newPass = ' NewPass88 '; c.formData.value.confirmPassword = ' NewPass88 ';
  await c.handleEditPass(f.validForm()); await settle();
  const writes = f.requests.filter(row => row.url === '/api/account/edit'); assert.equal(writes.length, 1);
  assert.deepEqual(JSON.parse(writes[0].data), { oldPass: ' OldPass77 ', newPass: ' NewPass88 ' });
  assert.equal(f.requests.some(row => row.url === '/api/account/logout'), false);
  assert.deepEqual(f.dispatches, ['app/logout']); assert.equal(c.editPassVisible.value, false);
  assert.equal(c.formData.value.oldPass, ''); assert.equal(c.formData.value.newPass, ''); c.unmount();
});

test('password failure preserves inputs, unlocks retry and blocks duplicate writes', async () => {
  const f = fixture(), c = f.component(AVATAR), pending = deferred(); c.editPassShow();
  c.formData.value.oldPass = 'OldPass77'; c.formData.value.newPass = 'NewPass88'; c.formData.value.confirmPassword = 'NewPass88';
  f.reply(config => config.url === '/api/account/edit' ? pending.promise : { code: 200, data: {} });
  const first = c.handleEditPass(f.validForm()), duplicate = c.handleEditPass(f.validForm()); await settle();
  assert.equal(f.requests.filter(row => row.url === '/api/account/edit').length, 1);
  pending.resolve({ code: 409, msg: 'retry fixture' }); await Promise.all([first, duplicate]); await settle();
  assert.equal(c.loading.value, false); assert.equal(c.editPassVisible.value, true); assert.equal(c.formData.value.oldPass, 'OldPass77');
  f.reply(() => { throw new Error('synthetic transport failure'); });
  await c.handleEditPass(f.validForm()); await settle();
  assert.equal(c.loading.value, false); assert.equal(c.formData.value.newPass, 'NewPass88');
  assert.equal(f.validationErrors.length, 0); assert.equal(f.dispatches.length, 0); c.unmount();
});

test('password late success cannot clear a reopened form or log out its new intent', async () => {
  const f = fixture(), c = f.component(AVATAR), pending = deferred(); c.editPassShow();
  c.formData.value.oldPass = 'OldPass77'; c.formData.value.newPass = 'NewPass88'; c.formData.value.confirmPassword = 'NewPass88';
  f.reply(config => config.url === '/api/account/edit' ? pending.promise : { code: 200, data: {} });
  const sending = c.handleEditPass(f.validForm()); await settle();
  c.editPassVisible.value = false; await settle(); c.editPassShow(); c.formData.value.newPass = 'LaterIntent99';
  pending.resolve({ code: 200 }); await sending; await settle();
  assert.equal(c.editPassVisible.value, true); assert.equal(c.formData.value.newPass, 'LaterIntent99'); assert.equal(f.dispatches.length, 0); c.unmount();
});

test('stale password and elder write sessions are rejected at synchronous API entry and final dispatch', async () => {
  for (const [module, method, body] of [['apis/user', 'editPass', { oldPass: 'OldPass77', newPass: 'NewPass88' }],
    ['apis/elderRecord', 'editElder', { id: 17, name: 'fixture' }]]) {
    const f = fixture(), session = f.load('utils/authResource').captureResourceSession(); f.persist(1, 'session-b');
    assert.throws(() => { const request = f.load(module)[method](body, session); request?.catch(() => {}); });
    await settle(); assert.equal(f.requests.length, 0);
    const g = fixture(), original = g.load('utils/authResource').captureResourceSession();
    g.http.interceptors.request.use(config => { g.persist(1, 'session-b'); return config; });
    await assert.rejects(g.load(module)[method](body, original)); assert.equal(g.requests.length, 0);
  }
});

test('elder reads and every shared selector bind the original session at entry and dispatch', async () => {
  const selectors = ['accident', 'bookManage', 'checkContract', 'depositRecharge', 'nurseReserve', 'outward',
    'order', 'retreatApply', 'soldManage', 'visit'].map(name => ['apis/' + name, 'pageSearchElderByKey']);
  const endpoints = [...selectors, ['apis/aiScope', 'pageAccessibleElders'], ['apis/aiHealth', 'pageSearchHealthElder'],
    ['apis/aiMedication', 'pageSearchMedicationElder'], ['apis/elderRecord', 'pageElderByKey'],
    ['apis/elderRecord', 'getElderById'], ['apis/elderRecord', 'getElderRecordById']];
  for (const [module, method] of endpoints) {
    const active = fixture(), current = active.load('utils/authResource').captureResourceSession();
    await active.load(module)[method]({ pageNum: 1, pageSize: 10, elderId: 17 }, current);
    assert.equal(active.requests.length, 1, module);
    assert.equal(active.requests[0].headers.get('token'), 'session-a', module);
    const f = fixture(), session = f.load('utils/authResource').captureResourceSession(); f.persist(1, 'session-b');
    assert.throws(() => { const request = f.load(module)[method]({ pageNum: 1, pageSize: 10, elderId: 17 }, session); request?.catch(() => {}); }, module);
    await settle(); assert.equal(f.requests.length, 0, module);
    const g = fixture(), original = g.load('utils/authResource').captureResourceSession();
    g.http.interceptors.request.use(config => { g.persist(1, 'session-b'); return config; });
    await assert.rejects(g.load(module)[method]({ pageNum: 1, pageSize: 10, elderId: 17 }, original));
    assert.equal(g.requests.length, 0, module);
  }
});

test('late elder detail cannot reopen or overwrite a newer editor', async () => {
  const f = fixture(), pending = deferred(), c = f.component(EDITOR);
  f.reply(config => config.params?.elderId === 17 ? pending.promise : { code: 200, data: { id: 18, name: 'current' } });
  const params = id => ({ title: '编辑', isView: false, rowData: { id }, api: f.load('apis/elderRecord').editElder, getTableList: async () => {} });
  const older = c.acceptParams(params(17)); await settle(); await c.acceptParams(params(18));
  pending.resolve({ code: 200, data: { id: 17, name: 'stale' } }); await older;
  assert.equal(c.formData.value.id, 18); assert.equal(c.formData.value.name, 'current'); c.unmount();
});

test('elder editor preserves the failed form, prevents duplicate writes and permits retry', async () => {
  const f = fixture(), c = f.component(EDITOR), pending = deferred();
  f.reply(config => ({ code: 200, data: { id: 17, name: 'initial' } }));
  await c.acceptParams({ title: '编辑', isView: false, rowData: { id: 17 }, api: f.load('apis/elderRecord').editElder, getTableList: async () => {} });
  c.ruleFormRef.value = f.validForm(); c.formData.value.name = 'preserved';
  f.reply(config => config.method === 'put' ? pending.promise : { code: 200, data: {} });
  const first = c.handleSubmit(), second = c.handleSubmit(); await settle();
  assert.equal(f.requests.filter(row => row.method === 'put').length, 1);
  pending.reject(new Error('synthetic editor network failure')); await Promise.all([first, second]); await settle();
  assert.equal(c.dialogVisible.value, true); assert.equal(c.formData.value.name, 'preserved');
  f.reply(() => ({ code: 409, msg: 'keep retry form' })); await c.handleSubmit(); await settle();
  assert.equal(f.requests.filter(row => row.method === 'put').length, 2);
  assert.equal(c.dialogVisible.value, true); assert.equal(c.formData.value.name, 'preserved'); c.unmount();
});

test('elder selector cannot emit a previous account resident after storage changes', async () => {
  const f = fixture(), c = f.component(SELECTOR);
  await c.elderAcceptParams({ elderApi: f.load('apis/checkContract').pageSearchElderByKey });
  f.persist(1, 'session-b'); f.event('storage'); await settle(); c.checkElder({ id: 17 });
  assert.equal(c.emitted.length, 0); assert.equal(c.dialogVisible.value, false); c.unmount();
});
