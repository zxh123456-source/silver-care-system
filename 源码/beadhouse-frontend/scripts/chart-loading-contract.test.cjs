const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pathToFileURL } = require('node:url');
const ts = require('typescript');
const vue = require('vue');
const { parse } = require('@vue/compiler-sfc');
const root = path.resolve(__dirname, '..');
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const settle = async () => { for (let i = 0; i < 5; i++) await vue.nextTick(); };

// Run the actual registered ECharts core. Only module specifier resolution is
// adapted for the in-memory TypeScript entry; no chart/component installer is mocked.
async function chartEngine() {
  const source = fs.readFileSync(path.join(root, 'src/utils/charts.ts'), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText
    .replace(/(['"])(echarts(?:\/[^'"]+)?)\1/g, (_, quote, name) => JSON.stringify(pathToFileURL(require.resolve(name)).href));
  return import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'));
}

test('the production adapter registers a real Canvas line chart with grid and axis tooltip', async () => {
  const engine = await chartEngine();
  const drawn = [];
  // Canvas2D is the surface boundary; ECharts' model, layout, registrations and
  // Canvas painter all execute, and must issue real path/text drawing commands.
  const context = new Proxy({ measureText: text => ({ width: String(text).length * 7 }), getLineDash: () => [] }, {
    get(target, key) { return key in target ? target[key] : (...args) => { drawn.push([key, args]); }; }
  });
  const canvas = { nodeName: 'CANVAS', width: 400, height: 300, style: {}, getContext: () => context };
  context.canvas = canvas;
  const chart = engine.init(canvas, 'purple-passion', { ssr: true, width: 400, height: 300 });
  try {
    chart.setOption({ animation: false, tooltip: { trigger: 'axis' }, grid: { left: 55, bottom: 55 },
      xAxis: { type: 'category', data: ['one', 'two', 'three'], axisLabel: { rotate: 30 } },
      yAxis: { type: 'value', name: 'temperature' },
      series: [{ type: 'line', smooth: true, connectNulls: true, data: [36.5, null, 37.1], label: { show: true, formatter: '{c}' } }] });
    assert.equal(chart.getZr().painter.getType(), 'canvas');
    const model = chart.getModel();
    assert.equal(model.getSeriesByIndex(0).subType, 'line');
    assert.equal(model.getSeriesByIndex(0).coordinateSystem.type, 'cartesian2d');
    assert.equal(model.getComponent('tooltip').get('trigger'), 'axis');
    assert.equal(model.getComponent('yAxis').get('name'), 'temperature');
    assert.equal(model.getSeriesByIndex(0).get('connectNulls'), true);
    chart.resize({ width: 640, height: 360 });
    assert.equal(chart.getWidth(), 640);
    assert.ok(drawn.some(([name]) => name === 'lineTo' || name === 'bezierCurveTo'));
    assert.ok(drawn.some(([name]) => name === 'fillText'));
  } finally { chart.dispose(); }
  assert.equal(chart.isDisposed(), true);
});

function fixture(name, request, pendingLibrary) {
  const hooks = [], mounted = [], charts = [], observers = [], warnings = [], listeners = new Map();
  let imports = 0;
  const engine = { __esModule: true, init(dom, theme) {
    const chart = { dom, theme, options: [], resizes: 0, disposals: 0,
      getDom: () => dom, setOption(value) { this.options.push(value); },
      resize() { this.resizes++; }, dispose() { this.disposals++; } };
    charts.push(chart); return chart;
  } };
  if (pendingLibrary) pendingLibrary.promise.__esModule = true;
  const window = { onresize() {},
    addEventListener(event, fn) { const items = listeners.get(event) || new Set(); items.add(fn); listeners.set(event, items); },
    removeEventListener(event, fn) { listeners.get(event)?.delete(fn); } };
  const dependencies = {
    vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => hooks.push(fn), onUnmounted: fn => hooks.push(fn) },
    'element-plus': { ElMessage: { warning: message => warnings.push(message), success() {} } },
    axios: require('axios'), '@/components/elderListDialog/index.vue': {}, '@/apis/aiScope': {},
    '@/apis/home': { getBusinessTrend: request }, '@/apis/aiHealth': { getHealthTrend: request }
  };
  const filename = path.join(root, 'src/views', name === 'home' ? 'home/trend.vue' : 'ai/health/index.vue');
  const descriptor = parse(fs.readFileSync(filename, 'utf8')).descriptor;
  const exposed = name === 'home' ? 'Echarts,loadError' : 'form,trend,metric,chartRef,loadTrend,renderChart';
  const source = descriptor.scriptSetup.content + '\nmodule.exports={' + exposed + '};';
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true } }).outputText;
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, window,
    ResizeObserver: class { constructor(fn) { this.callback = fn; this.disconnected = 0; observers.push(this); } observe(dom) { this.dom = dom; } disconnect() { this.disconnected++; } },
    require(key) {
      if (key === 'echarts' || key === '@/utils/charts') { imports++; return pendingLibrary?.promise || engine; }
      assert.ok(Object.hasOwn(dependencies, key), 'Unexpected chart dependency ' + key);
      return dependencies[key];
    }
  }, { filename });
  return { ...module.exports, engine, charts, observers, warnings, window, get imports() { return imports; },
    mount: () => Promise.all(mounted.map(fn => fn())), event: event => listeners.get(event)?.forEach(fn => fn()),
    unmount() { hooks.forEach(fn => fn()); }, listeners };
}

test('home does not load a chart engine until valid data arrives and keeps the original chart behavior', async () => {
  const data = deferred(), f = fixture('home', () => data.promise);
  f.Echarts.value = { chart: true };
  const previous = f.window.onresize, mounting = f.mount();
  assert.equal(f.imports, 0);
  data.resolve({ code: 200, data: [{ month: 'one', consultNum: 3 }] }); await mounting;
  assert.equal(f.imports, 1); assert.equal(f.charts.length, 1);
  assert.equal(f.charts[0].theme, 'purple-passion');
  assert.equal(f.charts[0].options[0].series[0].type, 'line');
  assert.equal(f.charts[0].options[0].series[0].label.show, true);
  f.event('resize'); assert.equal(f.charts[0].resizes, 1);
  f.unmount(); f.event('resize'); assert.equal(f.charts[0].disposals, 1); assert.equal(f.charts[0].resizes, 1);
  assert.equal(f.window.onresize, previous);
});

test('home data or module arrival after unmount never creates a chart', async () => {
  for (const boundary of ['data', 'module']) {
    const data = deferred(), library = deferred(), f = fixture('home', () => data.promise, library);
    f.Echarts.value = { chart: true }; const mounting = f.mount();
    if (boundary === 'module') { data.resolve({ code: 200, data: [] }); await settle(); }
    f.unmount(); f.Echarts.value = null;
    data.resolve({ code: 200, data: [] }); library.resolve(f.engine);
    await mounting; assert.equal(f.charts.length, 0);
  }
});

test('home data failure reports its existing error without fetching charts', async () => {
  const f = fixture('home', async () => ({ code: 500, data: [] })); f.Echarts.value = { chart: true };
  await f.mount(); assert.equal(f.imports, 0); assert.ok(f.loadError.value); f.unmount();
});

test('health loads no chart code for empty data and preserves metrics, resize and release for populated data', async () => {
  let records = [];
  const f = fixture('health', async () => ({ code: 200, data: { records } }));
  f.form.value.elderId = 7; f.chartRef.value = { chart: true };
  assert.equal(f.imports, 0); await f.loadTrend(); assert.equal(f.imports, 0);
  records = [{ measureTime: 'one', temperature: 36.5, heartRate: 72 }, { measureTime: 'two', temperature: null, heartRate: 74 }];
  await f.loadTrend(); assert.equal(f.charts.length, 1); assert.equal(f.imports, 1);
  assert.equal(f.charts[0].options[0].series[0].connectNulls, true);
  f.metric.value = 'heartRate'; await f.renderChart();
  assert.equal(f.imports, 1, 'An existing chart does not need another engine import');
  assert.deepEqual(Array.from(f.charts[0].options.at(-1).series[0].data), [72, 74]);
  f.event('resize'); assert.equal(f.charts[0].resizes, 1);
  f.unmount(); f.event('resize'); assert.equal(f.charts[0].disposals, 1); assert.equal(f.charts[0].resizes, 1);
  assert.ok(f.observers.every(item => item.disconnected > 0));
});

test('health module arrival after unmount cannot initialize a chart or attach an observer', async () => {
  const library = deferred(), f = fixture('health', async () => ({ code: 200, data: { records: [{ measureTime: 'one', temperature: 36.5 }] } }), library);
  f.form.value.elderId = 7; f.chartRef.value = { chart: true };
  const loading = f.loadTrend(); await settle();
  f.unmount(); f.chartRef.value = null; library.resolve(f.engine); await loading;
  assert.equal(f.charts.length, 0); assert.equal(f.observers.length, 0);
});

test('health data arriving after unmount does not replace state or load a chart engine', async () => {
  const data = deferred(), f = fixture('health', () => data.promise);
  f.form.value.elderId = 7; f.chartRef.value = { chart: true };
  const loading = f.loadTrend(); f.unmount(); f.chartRef.value = null;
  data.resolve({ code: 200, data: { records: [{ measureTime: 'one', temperature: 36.5 }] } });
  await loading;
  assert.equal(f.trend.value, null); assert.equal(f.imports, 0); assert.equal(f.charts.length, 0);
});

test('overlapping health module loads initialize only the current DOM and metric', async () => {
  const library = deferred(), f = fixture('health', async () => ({}), library);
  f.trend.value = { records: [{ measureTime: 'one', temperature: 36.5, heartRate: 72 }] };
  f.chartRef.value = { chart: 'old' }; const older = f.renderChart();
  f.chartRef.value = { chart: 'new' }; f.metric.value = 'heartRate'; const newer = f.renderChart();
  library.resolve(f.engine); await Promise.all([older, newer]);
  assert.equal(f.charts.length, 1); assert.equal(f.charts[0].dom, f.chartRef.value);
  assert.deepEqual(Array.from(f.charts[0].options[0].series[0].data), [72]);
  assert.equal(f.observers.length, 1); f.unmount(); assert.equal(f.charts[0].disposals, 1);
});

test('a health chart module fetch failure gives feedback without initializing a chart', async () => {
  const library = deferred(), f = fixture('health', async () => ({ code: 200, data: { records: [{ measureTime: 'one', temperature: 36.5 }] } }), library);
  f.form.value.elderId = 7; f.chartRef.value = { chart: true };
  const loading = f.loadTrend(); await settle(); library.reject(new Error('module unavailable'));
  await loading; assert.equal(f.warnings.length, 1); assert.equal(f.charts.length, 0); f.unmount();
});
