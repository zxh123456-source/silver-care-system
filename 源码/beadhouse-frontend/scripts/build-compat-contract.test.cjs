const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');
const dependencyRequire = process.env.FRONTEND_TEST_DEPENDENCIES
  ? require('node:module').createRequire(path.resolve(process.env.FRONTEND_TEST_DEPENDENCIES, 'package.json')) : require;
const ts = dependencyRequire('typescript');
const { parse } = dependencyRequire('@vue/compiler-sfc');

test('Element Plus icon consumers have local bindings without registering the entire icon library', () => {
  const { babelParse } = dependencyRequire('@vue/compiler-sfc');
  const icons = dependencyRequire('@element-plus/icons-vue');
  const sourceRoot = path.join(__dirname, '../src');
  let consumers = 0;
  for (const file of fs.readdirSync(sourceRoot, { recursive: true }).filter(file => file.endsWith('.vue'))) {
    const filename = path.join(sourceRoot, file);
    const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename });
    const bindings = new Set();
    for (const script of [descriptor.script, descriptor.scriptSetup].filter(Boolean)) {
      const ast = babelParse(script.content, { sourceType: 'module', plugins: ['typescript', ...(script.lang === 'tsx' ? ['jsx'] : [])] });
      for (const node of ast.program.body) {
        if (node.type === 'ImportDeclaration') node.specifiers.forEach(binding => bindings.add(binding.local.name));
        if (node.type === 'VariableDeclaration') node.declarations.forEach(declaration => {
          if (declaration.id.type === 'Identifier') bindings.add(declaration.id.name);
        });
      }
    }
    function visit(node) {
      if (node.type === 1 && Object.hasOwn(icons, node.tag)) {
        consumers++;
        assert.ok(bindings.has(node.tag), `${file}: ${node.tag} must have a local binding`);
      }
      if (node.type === 1 && /^(el-|El[A-Z])/.test(node.tag)) {
        for (const prop of node.props || []) {
          if (prop.type === 7 && prop.name === 'bind' && prop.arg && /(^|-)icon$/.test(prop.arg.content)
            && prop.exp && Object.hasOwn(icons, prop.exp.content)) {
            assert.ok(bindings.has(prop.exp.content), `${file}: ${prop.exp.content} must be bound locally`);
          }
          if (prop.type !== 6 || !/(^|-)icon$/.test(prop.name) || !prop.value) continue;
          const icon = prop.value.content.replace(/(^|[-_])(\w)/g, (_, separator, letter) => letter.toUpperCase());
          assert.ok(!Object.hasOwn(icons, icon), `${file}: ${prop.name}="${prop.value.content}" relies on global icon registration; bind the component`);
        }
      }
      (node.children || []).forEach(visit);
    }
    if (descriptor.template) visit(descriptor.template.ast);
  }
  assert.ok(consumers > 0);
});

test('Element Plus renders a directly bound icon without registering all global icons', async () => {
  const { createSSRApp, h } = dependencyRequire('vue');
  const { renderToString } = dependencyRequire('vue/server-renderer');
  const { ElButton, default: ElementPlus } = dependencyRequire('element-plus');
  const { Plus } = dependencyRequire('@element-plus/icons-vue');
  const app = createSSRApp({ render: () => h(ElButton, { icon: Plus }, () => '添加') });
  app.use(ElementPlus);
  assert.equal(app.component('Plus'), undefined);
  assert.match(await renderToString(app), /<svg\b[^>]*viewBox=/);
});

test('the configured Chinese locale resolves through the actual ESM package exports', () => {
  const app = fs.readFileSync(path.join(__dirname, '../src/App.vue'), 'utf8');
  const specifier = app.match(/import zhCn from ['"]([^'"]+)['"]/)[1];
  const result = spawnSync(process.execPath, ['--input-type=module', '-e',
    'const {default:locale}=await import(process.argv[1]); if(locale.name!=="zh-cn"||!locale.el.pagination) throw new Error("Chinese locale missing");',
    specifier], { cwd: process.env.FRONTEND_TEST_DEPENDENCIES || path.join(__dirname, '..'),
    encoding: 'utf8', timeout: 30000, windowsHide: true });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
});

// Substitute the bundler's module discovery and imported UI/asset boundaries;
// execute the shipped TypeScript rather than a second implementation of its logic.
function execute(relative, imports, extra = {}, suffix = '') {
  const filename = path.join(__dirname, '../src', relative);
  let source = fs.readFileSync(filename, 'utf8');
  if (relative.endsWith('.vue')) source = parse(source).descriptor.scriptSetup.content;
  const module = { exports: {} };
  const code = ts.transpileModule(source.replaceAll('import.meta.glob', '__glob') + suffix, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true }
  }).outputText;
  vm.runInNewContext(code, { module, exports: module.exports, require(name) {
    assert.ok(Object.hasOwn(imports, name), `Unexpected dependency: ${name}`);
    return imports[name];
  }, ...extra }, { filename });
  return module.exports;
}

function routes() {
  const loaded = [], added = [];
  const component = { name: 'Daily' };
  const modules = { '/src/views/ai/daily/index.vue': async () => { loaded.push('daily'); return component; } };
  const store = { state: { app: { routeTree: [] } }, async dispatch() {} };
  const route = { addRoute(...args) { added.push(args); } };
  const api = execute('router/utils.ts', {
    './index': { default: route, __esModule: true }, '@/store': { default: store, __esModule: true },
    '@/utils/commonUtil': { arrayDeduplicationByFiled: value => value }
  }, { __glob(pattern, options) {
    assert.equal(pattern, '/src/views/**/*.vue');
    assert.equal(options.import, 'default');
    assert.notEqual(options.eager, true);
    return modules;
  } });
  return { api, loaded, added, component, store };
}

test('backend component paths with optional leading slash and .vue resolve lazily', async () => {
  const f = routes();
  for (const component of ['ai/daily/index', '/ai/daily/index', 'ai/daily/index.vue', '/ai/daily/index.vue']) {
    const [route] = f.api.generateRouter([{ path: '/daily', name: 'Daily', component }]);
    const before = f.loaded.length;
    assert.equal(await route.component(), f.component);
    assert.equal(f.loaded.length, before + 1);
  }
});

test('nested routes preserve metadata and do not import views during route registration', async () => {
  const f = routes();
  const meta = { title: '每日助手' };
  f.store.state.app.routeTree = [{ path: '/ai', component: '/ai/index', children: [
    { path: '/daily', name: 'Daily', component: '/ai/daily/index', meta }
  ] }];
  await f.api.initRoutes();
  assert.equal(f.loaded.length, 0);
  assert.equal(f.added.length, 1);
  assert.equal(f.added[0][0], 'Layout');
  const child = f.added[0][1].children[0];
  assert.equal(child.meta, meta);
  assert.equal(await child.component(), f.component);
});

test('an unavailable historical route rejects only when visited and does not block known routes', async () => {
  const f = routes();
  f.store.state.app.routeTree = [{ path: '/old', component: '/removed/index' },
    { path: '/daily', component: '/ai/daily/index' }];
  await f.api.initRoutes();
  assert.equal(f.added.length, 2);
  await assert.rejects(f.added[0][1].component(), /\/removed\/index/);
  assert.equal(await f.added[1][1].component(), f.component);
});

function element(name, attributes = {}, children = []) {
  const attrs = new Map(Object.entries(attributes));
  return { nodeName: name, childNodes: children,
    get attributes() { return Array.from(attrs, ([name, value]) => ({ name, value })); },
    setAttribute(name, value) { attrs.set(name, value); },
    getAttribute(name) { return attrs.get(name) ?? null; },
    appendChild(child) { this.childNodes.push(child); return child; },
    cloneNode() { return element(name, Object.fromEntries(attrs), this.childNodes.map(child => child.cloneNode(true))); }
  };
}

test('SVG registration preserves symbol references, viewBox, paint and paths without duplicating the sprite', () => {
  const pathNode = element('path', { d: 'M1 2L3 4', stroke: 'currentColor' });
  const source = element('svg', { viewBox: '0 0 48 48', width: '24', height: '24', fill: 'none' }, [pathNode]);
  const legacySource = element('svg', { width: '130', height: '130' }, [pathNode]);
  const body = element('body');
  const document = { body, createElementNS: (_, name) => element(name),
    importNode: node => node.cloneNode(true),
    getElementById(id) { return body.childNodes.find(node => node.getAttribute('id') === id) ?? null; } };
  const registered = [];
  const { default: register } = execute('icons/index.ts', {
    '@/components/SvgIcon/index.vue': { default: 'SvgIcon', __esModule: true }
  }, { document, DOMParser: class { parseFromString(raw, type) {
    assert.equal(type, 'image/svg+xml');
    return { documentElement: raw === '<svg legacy>' ? legacySource : source };
  } }, __glob(pattern, options) {
    assert.equal(pattern, './svg/*.svg'); assert.equal(options.eager, true);
    assert.equal(options.query, '?raw'); assert.equal(options.import, 'default');
    return { './svg/home.svg': '<svg fixture>', './svg/user.svg': '<svg legacy>' };
  } });
  const app = { component(...args) { registered.push(args); } };
  register(app); register(app);
  assert.equal(body.childNodes.length, 1);
  const sprite = body.childNodes[0], symbol = sprite.childNodes[0];
  assert.equal(sprite.childNodes.length, 2);
  assert.equal(sprite.getAttribute('aria-hidden'), 'true');
  assert.equal(symbol.nodeName, 'symbol');
  assert.equal(symbol.getAttribute('id'), 'icon-home');
  assert.equal(symbol.getAttribute('viewBox'), '0 0 48 48');
  assert.equal(symbol.getAttribute('fill'), 'none');
  assert.equal(symbol.childNodes[0].getAttribute('d'), 'M1 2L3 4');
  assert.equal(sprite.childNodes[1].getAttribute('id'), 'icon-user');
  assert.equal(sprite.childNodes[1].getAttribute('viewBox'), '0 0 130 130');
  assert.deepEqual(registered, [['svg-icon', 'SvgIcon'], ['svg-icon', 'SvgIcon']]);
});

test('bed portraits retain male, female and fallback asset branches with static imports', () => {
  const images = { '@/assets/imgs/img.png': '/assets/man.hash.png',
    '@/assets/imgs/img_1.png': '/assets/woman.hash.png', '@/assets/imgs/img_2.png': '/assets/unknown.hash.png' };
  const imports = Object.fromEntries(Object.entries(images).map(([name, value]) => [name, { default: value, __esModule: true }]));
  const api = execute('views/live/bed/index.vue', { ...imports,
    vue: { onMounted() {}, ref: value => ({ value }) }, '@element-plus/icons-vue': {}, '@/apis/bedPanorama': {}
  }, {}, '\nmodule.exports = { getImgUrl };');
  assert.equal(api.getImgUrl('男'), images['@/assets/imgs/img.png']);
  assert.equal(api.getImgUrl('女'), images['@/assets/imgs/img_1.png']);
  assert.equal(api.getImgUrl(''), images['@/assets/imgs/img_2.png']);
  assert.equal(api.getImgUrl('其他'), images['@/assets/imgs/img_2.png']);
});
