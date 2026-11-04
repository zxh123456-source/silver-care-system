const assert = require('node:assert/strict');
const { test } = require('node:test');
const path = require('node:path');
const vm = require('node:vm');
const http = require('node:http');
const { once } = require('node:events');
const { createRequire } = require('node:module');
const { spawnSync } = require('node:child_process');
const dependencyRequire = process.env.FRONTEND_TEST_DEPENDENCIES
  ? createRequire(path.resolve(process.env.FRONTEND_TEST_DEPENDENCIES, 'package.json')) : require;

test('every lint glob consumer bounds globparts amplification and preserves source matching', () => {
  for (const consumer of ['eslint', '@eslint/config-array', '@typescript-eslint/typescript-estree']) {
    const consumerRequire = createRequire(dependencyRequire.resolve(consumer));
    const modulePath = consumerRequire.resolve('minimatch');
    const { minimatch, Minimatch } = consumerRequire('minimatch');
    assert.equal(minimatch('src/views/ai/care/index.vue', 'src/**/*.{vue,ts}'), true);
    assert.equal(minimatch('src/assets/care.png', 'src/**/*.{vue,ts}'), false);
    const ordinary = new Minimatch('**/../a/b/**/../c/d', { optimizationLevel: 2 });
    assert.equal(ordinary.match('../a/b/../c/d'), true);
    assert.equal(ordinary.match('x/a/b/y/c/d'), true);
    // Advisory PoC: limit heap/time as well as expansion count on an old lockfile.
    const code = 'const {Minimatch}=require(process.argv[1]);const m=new Minimatch(Array(30).fill("**/../a/b").join("/"),{optimizationLevel:2,braceExpandMax:64});process.stdout.write(String(m.set.length));';
    const result = spawnSync(process.execPath, ['--max-old-space-size=64', '-e', code, modulePath],
      { encoding: 'utf8', timeout: 3000, windowsHide: true });
    assert.equal(result.status, 0, `${consumer} must finish bounded preprocessing: ${result.error?.code || result.signal || result.status}`);
    assert.equal(Number(result.stdout), 64, `${consumer} must honor braceExpandMax`);
  }
});

test('follow-redirects strips custom cross-realm credentials on a real cross-origin redirect', async () => {
  const follow = dependencyRequire('follow-redirects');
  const received = [];
  const destination = http.createServer((req, res) => { received.push(req.headers); res.end('ok'); });
  const source = http.createServer((_req, res) => {
    res.writeHead(302, { Location: `http://127.0.0.1:${destination.address().port}/target` }); res.end();
  });
  destination.listen(0, '127.0.0.1'); await once(destination, 'listening');
  source.listen(0, '127.0.0.1'); await once(source, 'listening');
  try {
    for (const sensitiveHeaders of [['X-API-Key'], vm.runInNewContext('["X-API-Key"]')]) {
      await new Promise((resolve, reject) => {
        const request = follow.http.get(`http://127.0.0.1:${source.address().port}/start`, {
          sensitiveHeaders, headers: { 'X-API-Key': 'fixture-secret', 'X-Harmless': 'retained' }
        }, response => { response.resume(); response.on('end', resolve); response.on('error', reject); });
        request.setTimeout(3000, () => request.destroy(new Error('redirect timed out')));
        request.on('error', reject);
      });
    }
    assert.equal(received.length, 2);
    for (const headers of received) {
      assert.equal(headers['x-api-key'], undefined);
      assert.equal(headers['x-harmless'], 'retained');
    }
  } finally {
    await Promise.all([new Promise(resolve => source.close(resolve)), new Promise(resolve => destination.close(resolve))]);
  }
});
