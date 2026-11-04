const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

function fixture(raw = null, faults = {}) {
  const values = new Map(raw === null ? [] : [['stateData', raw]]);
  const storage = {
    getItem(key) { if (faults.read) throw new Error('storage denied'); return values.get(key) ?? null; },
    setItem(key, value) { if (faults.write) throw new Error('quota exceeded'); values.set(key, String(value)); },
    removeItem: key => values.delete(key), clear: () => values.clear()
  };
  const modules = new Map();
  const window = { get localStorage() { if (faults.access) throw new Error('storage denied'); return storage; } };
  function load(relative) {
    if (modules.has(relative)) return modules.get(relative);
    const module = { exports: {} };
    const source = fs.readFileSync(path.join(__dirname, '..', 'src', relative + '.ts'), 'utf8');
    const code = ts.transpileModule(source, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true
    } }).outputText;
    vm.runInNewContext(code, { module, exports: module.exports, window, localStorage: storage, console,
      require(name) {
        if (name === './modules/app') return load('store/modules/app/index');
        if (name === './persistence') return load('store/persistence');
        if (name === '@/router') return { __esModule: true, default: { replace() {} } };
        if (name === '@/router/utils') return { clearRoutes() {}, formatRouteTree: value => value };
        if (name === '@/mock/getters') return { getRouterList: () => ({ data: [] }) };
        if (name === '@/apis/user') return {};
        return require(name);
      }
    }, { filename: relative + '.ts' });
    modules.set(relative, module.exports);
    return module.exports;
  }
  return { store: load('store/index').default, storage, values, load };
}

test('real Vuex store restores the existing stateData app protocol and persists mutations', async () => {
  const app = { uid: 2, token: 'session-a', hasAuth: true, routeTree: [{ id: 1, name: 'Care' }],
    siderType: false, userPeofile: { userid: 18, username: '护理员', avator: '/portrait.png', authIdList: [1, 2] }, rememberPWD: true };
  const f = fixture(JSON.stringify({ app }));
  assert.deepEqual(JSON.parse(JSON.stringify(f.store.state.app)), app);
  f.store.commit('app/setToken', 'session-b');
  f.store.commit('app/setSiderType');
  assert.equal(JSON.parse(f.storage.getItem('stateData')).app.token, 'session-b');
  assert.equal(JSON.parse(f.storage.getItem('stateData')).app.siderType, true);
  const session = f.load('utils/rechargeIntent');
  assert.equal(session.matchesPersistedRechargeSession({ staffId: '18', token: 'session-b' }), true);
  f.storage.setItem('stateData', JSON.stringify({ app: { token: 'other-tab', userPeofile: { userid: 19 } } }));
  assert.equal(session.matchesPersistedRechargeSession({ staffId: '18', token: 'session-b' }), false);
  await f.store.dispatch('app/logout');
  assert.equal(f.storage.getItem('stateData'), null);
  assert.equal(f.store.state.app.token, '');
});

test('persisted data cannot introduce root fields, prototype keys or invalid app field types', () => {
  const raw = '{"extraRoot":{"token":"bad"},"__proto__":{"polluted":true},"app":{"uid":"wrong","token":{},"hasAuth":"yes","routeTree":{},"siderType":null,"rememberPWD":1,"userPeofile":{"userid":7,"nested":{"__proto__":{"polluted":true}},"constructor":{"prototype":{"polluted":true}}},"unexpected":true,"prototype":{"polluted":true}}}';
  const f = fixture(raw), app = f.store.state.app;
  assert.equal(f.store.state.extraRoot, undefined);
  assert.equal(app.unexpected, undefined);
  assert.equal(app.uid, 1);
  assert.equal(app.token, '');
  assert.equal(app.hasAuth, false);
  assert.equal(app.siderType, true);
  assert.equal(app.rememberPWD, false);
  assert.equal(app.routeTree.length, 0);
  assert.equal(app.userPeofile.userid, 7);
  f.store.commit('app/setToken', 'safe');
  const written = f.storage.getItem('stateData');
  assert.doesNotMatch(written, /"(?:__proto__|constructor|prototype)"/);
  assert.equal({}.polluted, undefined);
});

test('missing, malformed and unavailable storage leaves the real store usable', () => {
  for (const raw of [null, 'broken-json', 'null', '[]', '{"app":null}', '{"app":[]}']) {
    const f = fixture(raw);
    assert.equal(f.store.state.app.token, '');
    f.store.commit('app/setToken', 'usable');
    assert.equal(f.store.state.app.token, 'usable');
  }
  for (const faults of [{ read: true }, { write: true }, { access: true }]) {
    const f = fixture(null, faults);
    assert.doesNotThrow(() => f.store.commit('app/setToken', 'in-memory'));
    assert.equal(f.store.state.app.token, 'in-memory');
  }
});
