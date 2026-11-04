const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { inspectBundle, assertBudget } = require('./bundle-budget.cjs');

test('initial-load accounting follows static imports once and excludes lazy chunks', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'silvercare-bundle-'));
  try {
    fs.mkdirSync(path.join(root, 'assets'));
    fs.writeFileSync(path.join(root, 'index.html'), '<script type="module" src="/assets/app.js"></script>');
    const files = { 'app.js': 'import "./shared.js"; import "./cycle.js"; import("./chart.js");',
      'shared.js': 'export const x = "护理";', 'cycle.js': 'import "./app.js"; import "./shared.js";',
      'chart.js': 'export const chart = 1;' };
    for (const [name, code] of Object.entries(files)) fs.writeFileSync(path.join(root, 'assets', name), code);
    const result = inspectBundle(root);
    assert.deepEqual(result.initialFiles, ['assets/app.js', 'assets/cycle.js', 'assets/shared.js']);
    assert.equal(result.initialBytes, Buffer.byteLength(files['app.js'] + files['shared.js'] + files['cycle.js']));
    assert.equal(result.chunks.length, 4);
    assert.ok(result.initialGzipBytes > 0);
    assertBudget(result);
  } finally {
    const target = fs.realpathSync(root);
    assert.equal(path.dirname(target), fs.realpathSync(os.tmpdir()));
    assert.ok(path.basename(target).startsWith('silvercare-bundle-'));
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('the default 500kB budget rejects oversized output independently of compression', () => {
  assertBudget({ chunks: [{ file: 'ok.js', bytes: 500_000, gzipBytes: 10 }] });
  assert.throws(() => assertBudget({ chunks: [{ file: 'large.js', bytes: 500_001, gzipBytes: 10 }] }), /large.js.*500001B/);
});
