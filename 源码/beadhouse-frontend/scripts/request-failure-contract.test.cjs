const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const deps = process.env.FRONTEND_TEST_DEPENDENCIES
  ? require('node:module').createRequire(path.resolve(process.env.FRONTEND_TEST_DEPENDENCIES, 'package.json')) : require;
const ts = deps('typescript'), vue = deps('vue'), axios = deps('axios');
const { parse } = deps('@vue/compiler-sfc');

function execute(source, filename, imports, extra = {}) {
  const module = { exports: {} };
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true
  } }).outputText;
  vm.runInNewContext(code, { module, exports: module.exports,
    require: name => Object.hasOwn(imports, name) ? imports[name] : deps(name), ...extra }, { filename });
  return module.exports;
}

function failureBoundary() {
  const filename = path.join(__dirname, '../src/utils/requestFailure.ts');
  return execute(fs.readFileSync(filename, 'utf8'), filename, { axios }).handleRequestFailure;
}

test('request failure boundary consumes Axios transport errors and explicit cancellation', () => {
  const handle = failureBoundary();
  for (const error of [new axios.AxiosError('offline', 'ERR_NETWORK'),
    new axios.CanceledError('request canceled'), { __CANCEL__: true }]) {
    assert.equal(handle(error), undefined);
  }
});

test('request failure boundary recognizes only the server code/message error contract', () => {
  const handle = failureBoundary();
  for (const code of [400, 401, 403, 500, 503, 599]) {
    assert.equal(handle({ code, msg: 'already reported by HTTP', data: null }), undefined);
  }
});

test('unknown failures are rethrown unchanged, including unsupported legacy err-only objects', () => {
  const handle = failureBoundary();
  for (const error of [new TypeError('programmer failure'), new Error('unexpected'),
    null, undefined, false, 'unexpected', {}, { err: 1, msg: 'not a server Result' },
    { code: '500', msg: 'wrong type' }, { code: 500 }, { code: 200, msg: 'success' },
    { code: 600, msg: 'out of contract' }, { code: 400.5, msg: 'not an integer' }]) {
    let caught = false;
    try { handle(error); }
    catch (actual) { caught = true; assert.equal(actual, error); }
    assert.equal(caught, true, 'The original unknown failure must remain observable');
  }
});

function bedForm() {
  const filename = path.join(__dirname, '../src/views/base/live/flat/dialog/bed.vue');
  const source = parse(fs.readFileSync(filename, 'utf8')).descriptor.scriptSetup.content;
  const logs = [], messages = [], emitted = [];
  const imports = { vue, 'element-plus': { ElMessage: {
    success: message => messages.push(message), error: message => messages.push(message)
  } }, '@/utils/formRules': {}, '@/apis/build': {},
    '@/utils/requestFailure': new Proxy({}, { get: (_, name) => name === 'handleRequestFailure' ? failureBoundary() : undefined }) };
  const scope = vue.effectScope();
  const c = scope.run(() => execute(source +
    '\nmodule.exports={handleSubmit,ruleFormRef,drawerProps,formData,dialogVisible};', filename, imports, {
    defineExpose() {}, defineEmits: () => (...args) => emitted.push(args),
    console: { log: (...args) => logs.push(args), warn: (...args) => logs.push(args), error: (...args) => logs.push(args) }
  }));
  let pending;
  c.ruleFormRef.value = { validate(callback) { pending = Promise.resolve().then(() => callback(true)); return pending; } };
  c.dialogVisible.value = true;
  c.formData.value = { id: '17', name: 'unchanged bed', roomId: '5', bedFlag: '空闲' };
  return { ...c, logs, messages, emitted,
    submit() { c.handleSubmit(); return pending; }, stop() { scope.stop(); } };
}

test('the real bed form retains failed values and permits retry after handled request failures', async () => {
  for (const error of [new axios.AxiosError('offline', 'ERR_NETWORK'),
    { code: 403, msg: 'scope denied' }, new axios.CanceledError('canceled')]) {
    const c = bedForm(), submissions = [];
    c.drawerProps.value = { title: 'edit', api: async data => {
      submissions.push(JSON.parse(JSON.stringify(data))); throw error;
    } };
    try {
      await c.submit();
      assert.equal(c.dialogVisible.value, true);
      assert.equal(c.formData.value.name, 'unchanged bed');
      assert.equal(c.emitted.length, 0);
      assert.equal(c.messages.length, 0, 'The HTTP layer already reports known failures');
      assert.equal(c.logs.length, 0, 'A handled API failure must not generate an additional console exception');
      c.drawerProps.value.api = async data => { submissions.push(JSON.parse(JSON.stringify(data))); return { code: 200, msg: 'saved' }; };
      await c.submit();
      assert.equal(c.dialogVisible.value, false);
      assert.deepEqual(submissions[1], submissions[0]);
      assert.equal(c.emitted[0][0], 'operateNode');
    } finally { c.stop(); }
  }
});

test('the real bed form propagates unexpected exceptions and preserves the unsaved form', async () => {
  const c = bedForm(), error = new TypeError('unexpected programmer failure');
  c.drawerProps.value = { title: 'edit', api: async () => { throw error; } };
  try {
    await assert.rejects(c.submit(), actual => actual === error);
    assert.equal(c.dialogVisible.value, true);
    assert.equal(c.formData.value.name, 'unchanged bed');
    assert.equal(c.emitted.length, 0);
  } finally { c.stop(); }
});
