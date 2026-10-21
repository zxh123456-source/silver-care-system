const fs = require('node:fs');
const path = require('node:path');
const { gzipSync } = require('node:zlib');
const { babelParse } = require('@vue/compiler-sfc');

// Match Vite's default warning limit; count actual emitted bytes, without changing it.
const chunkBudget = 500_000;

function inspectBundle(directory) {
  const root = path.resolve(directory);
  const chunks = new Map();
  for (const file of fs.readdirSync(root, { recursive: true }).filter(file => file.endsWith('.js'))) {
    const absolute = path.join(root, file);
    const code = fs.readFileSync(absolute, 'utf8');
    const imports = babelParse(code, { sourceType: 'module' }).program.body
      .filter(node => ['ImportDeclaration', 'ExportNamedDeclaration', 'ExportAllDeclaration'].includes(node.type) && node.source)
      .map(node => node.source.value).filter(source => source.startsWith('.'))
      .map(source => path.resolve(path.dirname(absolute), source));
    chunks.set(absolute, { file: file.replaceAll('\\', '/'), bytes: Buffer.byteLength(code),
      gzipBytes: gzipSync(code).length, imports });
  }
  const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
  const entry = html.match(/<script\b[^>]*\bsrc="([^"]+)"/);
  if (!entry) throw new Error('Built entry script is missing');
  const initial = new Set();
  function visit(file) {
    if (initial.has(file)) return;
    const chunk = chunks.get(file);
    if (!chunk) throw new Error(`Missing static JavaScript dependency: ${path.relative(root, file)}`);
    initial.add(file);
    chunk.imports.forEach(visit);
  }
  visit(path.join(root, new URL(entry[1], 'http://bundle.local').pathname.replace(/^\/+/, '')));
  const sum = (files, key) => files.reduce((total, file) => total + chunks.get(file)[key], 0);
  const values = [...chunks.values()];
  return {
    limitBytes: chunkBudget,
    chunks: values.map(({ imports, ...chunk }) => chunk).sort((a, b) => b.bytes - a.bytes),
    totalBytes: sum([...chunks.keys()], 'bytes'),
    totalGzipBytes: sum([...chunks.keys()], 'gzipBytes'),
    initialFiles: [...initial].map(file => chunks.get(file).file).sort(),
    initialBytes: sum([...initial], 'bytes'),
    initialGzipBytes: sum([...initial], 'gzipBytes'),
    cssBytes: fs.readdirSync(root, { recursive: true }).filter(file => file.endsWith('.css'))
      .reduce((total, file) => total + fs.statSync(path.join(root, file)).size, 0)
  };
}

function assertBudget(stats) {
  const oversized = stats.chunks.filter(chunk => chunk.bytes > chunkBudget);
  if (oversized.length) {
    throw new Error(`JavaScript chunks exceed the default 500kB budget: ${oversized.map(chunk => `${chunk.file} (${chunk.bytes}B)`).join(', ')}`);
  }
}

if (require.main === module) {
  const current = inspectBundle(process.argv[2] || path.join(__dirname, '../dist'));
  const { chunks, ...summary } = current;
  console.log(JSON.stringify({ ...summary, chunkCount: chunks.length, largestChunks: chunks.slice(0, 5) }, null, 2));
  if (process.argv[3]) {
    const before = inspectBundle(process.argv[3]);
    console.log(JSON.stringify({ before: { initialBytes: before.initialBytes, initialGzipBytes: before.initialGzipBytes,
      totalBytes: before.totalBytes, totalGzipBytes: before.totalGzipBytes, cssBytes: before.cssBytes },
    reductions: { initialBytes: before.initialBytes - current.initialBytes,
      initialGzipBytes: before.initialGzipBytes - current.initialGzipBytes,
      totalBytes: before.totalBytes - current.totalBytes, totalGzipBytes: before.totalGzipBytes - current.totalGzipBytes,
      cssBytes: before.cssBytes - current.cssBytes } }, null, 2));
  }
  assertBudget(current);
}

module.exports = { inspectBundle, assertBudget };
