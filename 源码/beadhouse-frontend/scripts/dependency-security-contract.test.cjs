const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { spawnSync } = require('node:child_process');

const frontend = path.resolve(__dirname, '..');
const dependencyRequire = process.env.FRONTEND_TEST_DEPENDENCIES
  ? createRequire(path.resolve(process.env.FRONTEND_TEST_DEPENDENCIES, 'package.json')) : require;
const postcss = dependencyRequire('postcss');
const czRequire = createRequire(dependencyRequire.resolve('cz-customizable'));
const inquirer = czRequire('inquirer');
const inquirerRequire = createRequire(czRequire.resolve('inquirer'));
const externalRequire = createRequire(inquirerRequire.resolve('external-editor'));
const { ExternalEditor } = inquirerRequire('external-editor');
const tmp = externalRequire('tmp');

test('patched picomatch preserves source globs without inheriting POSIX class methods', () => {
  const componentRequire = createRequire(dependencyRequire.resolve('unplugin-vue-components'));
  const picomatch = componentRequire('picomatch');
  const matches = picomatch('src/**/*.{vue,ts}');
  assert.equal(matches('src/views/live/bed/index.vue'), true);
  assert.equal(matches('src/apis/bed.ts'), true);
  assert.equal(matches('src/assets/bed.png'), false);
  assert.equal(picomatch('[[:alpha:]]')('A'), true);
  assert.equal(picomatch('[[:alpha:]]')('1'), false);
  for (const name of ['constructor', 'toString', 'hasOwnProperty']) {
    assert.doesNotMatch(picomatch.makeRe(`[[:${name}:]]`).source, /function|native code/,
      `Inherited ${name} must not become a regular-expression character class`);
  }
});

test('patched legacy brace expansion preserves numeric/list patterns and terminates zero-step sequences', () => {
  const expand = dependencyRequire('brace-expansion');
  assert.deepEqual(expand('care-{01..03}.{vue,ts}'), ['care-01.vue', 'care-01.ts', 'care-02.vue',
    'care-02.ts', 'care-03.vue', 'care-03.ts']);
  // Bound both time and heap so this regression cannot hang or exhaust the test runner on an old lockfile.
  const result = spawnSync(process.execPath, ['--max-old-space-size=64', '-e',
    'process.stdout.write(JSON.stringify(require(process.argv[1])("{1..2..0}")))',
    dependencyRequire.resolve('brace-expansion')], { encoding: 'utf8', timeout: 2000, windowsHide: true });
  assert.equal(result.status, 0, `Zero-step expansion must finish safely (${result.error?.code || result.signal || result.status})`);
  assert.deepEqual(JSON.parse(result.stdout), ['1', '2']);
  const version = dependencyRequire('brace-expansion/package.json').version.split('.').map(Number);
  assert.ok(version[0] === 1 && (version[1] > 1 || version[1] === 1 && version[2] >= 21),
    `Legacy glob must use the patched brace-expansion 1.1.21+ line: ${version.join('.')}`);
});

test('the real Tailwind 4 entry preserves project colors, variants, nesting and important declarations', async () => {
  const tailwindPostcss = dependencyRequire('@tailwindcss/postcss');
  assert.match(dependencyRequire('tailwindcss/package.json').version, /^4\./);
  const candidates = ['hover:bg-red-400', 'focus:font-semibold', 'group-hover:text-blue',
    'peer-checked:block', '[&>svg]:h-4', 'group-hover:focus:!text-blue', '!mt-4', '[mask-type:luminance]',
    'bg-blue-400', 'bg-blue-50', 'bg-green-400', 'bg-red-600', 'bg-yellow-400', 'text-green-500', 'text-slate-400'];
  const sourcePath = path.join(frontend, 'src/styles/tailwind.css');
  // Keep the shipped stylesheet/config and resolve its package imports against the
  // selected physical dependency tree, including when the workspace has an old junction.
  const source = fs.readFileSync(sourcePath, 'utf8').replace(/(['"])tailwindcss\/([^'"]+)\1/g,
    (_, quote, suffix) => `${quote}${dependencyRequire.resolve(`tailwindcss/${suffix}`).replaceAll('\\', '/')}${quote}`);
  const input = `${source}\n@source inline("${candidates.join(' ')}");\n` +
    '.security-parent { & > .child:hover, &:focus { color: #123456; } }';
  const result = await postcss([tailwindPostcss({ base: frontend, optimize: { minify: false } })])
    .process(input, { from: sourcePath });
  assert.deepEqual(result.warnings(), []);
  const outsideSourceUtility = 'p-[879px]';
  assert.ok(result.css.includes('.mx-1'), 'Actual source classes must be discovered without inline safelisting');
  assert.ok(!result.css.includes('879px'), `${outsideSourceUtility} in test tooling must not enter application CSS`);
  function ruleFor(candidate) {
    const selector = `.${candidate.replace(/[^a-zA-Z0-9_-]/g, char => `\\${char}`)}`;
    const matches = [];
    result.root.walkRules(rule => {
      if (rule.selector.includes(selector)) matches.push(rule);
    });
    assert.equal(matches.length, 1, `Expected exactly one rule for ${candidate}`);
    const rule = matches[0];
    for (let parent = rule.parent; parent; parent = parent.parent) {
      assert.ok(parent.type !== 'atrule' || parent.name !== 'layer', `${candidate} must retain unlayered precedence`);
    }
    return rule;
  }
  function variable(name) {
    let value;
    result.root.walkDecls(name, decl => { value = decl.value; });
    assert.ok(value, `Expected theme variable ${name}`);
    return value;
  }
  function declaration(candidate, prop, expected, important = false) {
    const rule = ruleFor(candidate);
    const decl = rule.nodes.find(node => node.type === 'decl' && node.prop === prop);
    assert.ok(decl, `${candidate} must emit ${prop}`);
    const resolved = decl.value.replace(/^var\((--[^)]+)\)$/, (_, name) => variable(name));
    if (expected instanceof RegExp) assert.match(resolved, expected);
    else assert.equal(resolved, expected);
    assert.equal(Boolean(decl.important), important, `${candidate}: ${prop} importance`);
  }
  declaration('hover:bg-red-400', 'background-color', '#f87171');
  assert.match(ruleFor('hover:bg-red-400').selector, /:hover/);
  declaration('focus:font-semibold', 'font-weight', '600');
  assert.match(ruleFor('focus:font-semibold').selector, /:focus/);
  declaration('group-hover:text-blue', 'color', '#409eff');
  assert.match(ruleFor('group-hover:text-blue').selector, /\.group\b.*:hover/);
  declaration('peer-checked:block', 'display', 'block');
  assert.match(ruleFor('peer-checked:block').selector, /\.peer\b.*:checked.*~/);
  assert.match(variable('--spacing'), /^(?:0)?\.25rem$/);
  declaration('[&>svg]:h-4', 'height', /^(?:1rem|calc\(var\(--spacing\)\s*\*\s*4\))$/);
  assert.match(ruleFor('[&>svg]:h-4').selector, />\s*svg\b/);
  declaration('group-hover:focus:!text-blue', 'color', '#409eff', true);
  assert.match(ruleFor('group-hover:focus:!text-blue').selector, /:hover/);
  assert.match(ruleFor('group-hover:focus:!text-blue').selector, /:focus/);
  declaration('!mt-4', 'margin-top', /^(?:1rem|calc\(var\(--spacing\)\s*\*\s*4\))$/, true);
  declaration('[mask-type:luminance]', 'mask-type', 'luminance');
  for (const [candidate, prop, color] of [
    ['bg-blue-400', 'background-color', '#60a5fa'], ['bg-blue-50', 'background-color', '#eff6ff'],
    ['bg-green-400', 'background-color', '#4ade80'], ['bg-red-600', 'background-color', '#dc2626'],
    ['bg-yellow-400', 'background-color', '#facc15'], ['text-green-500', 'color', '#22c55e'],
    ['text-slate-400', 'color', '#94a3b8']
  ]) declaration(candidate, prop, color);
  assert.equal(variable('--color-gray-200'), '#e5e7eb');
  let universalBorder;
  let buttonCursor;
  result.root.walkRules(rule => {
    for (const decl of rule.nodes.filter(node => node.type === 'decl')) {
      if (/(?:^|,)\s*\*(?:,|$)/.test(rule.selector) && decl.prop === 'border-color') universalBorder = decl.value;
      if (rule.selector.includes('button:not(:disabled)') && decl.prop === 'cursor') buttonCursor = decl.value;
    }
  });
  assert.equal(universalBorder, 'var(--color-gray-200, currentColor)', 'The last universal border rule must retain the v3 default');
  assert.equal(buttonCursor, 'pointer', 'Enabled buttons must retain their pointer cursor');
  const nested = [];
  result.root.walkRules(rule => { if (rule.selector.includes('.security-parent')) nested.push(rule); });
  assert.equal(nested.length, 1);
  assert.match(nested[0].selector, /\.security-parent\s*>\s*\.child:hover/);
  assert.match(nested[0].selector, /\.security-parent:focus/);
  assert.equal(nested[0].nodes[0].value, '#123456');
});

test('external-editor uses the patched tmp API for real UTF-8 file creation, readback and cleanup', () => {
  // Exercise file handling directly; run/runAsync would launch the user's configured editor.
  const editor = new ExternalEditor('初始提交说明', { prefix: 'silver-care-security-', postfix: '.txt', mode: 0o600 });
  try {
    assert.equal(fs.readFileSync(editor.tempFile, 'utf8'), '初始提交说明');
    assert.ok(path.basename(editor.tempFile).startsWith('silver-care-security-'));
    assert.ok(editor.tempFile.endsWith('.txt'));
    fs.writeFileSync(editor.tempFile, 'feat(依赖): 修复安全告警\n\n保留中文提交配置。', 'utf8');
    editor.readTemporaryFile();
    assert.equal(editor.text, 'feat(依赖): 修复安全告警\n\n保留中文提交配置。');
  } finally {
    editor.cleanup();
  }
  assert.equal(fs.existsSync(editor.tempFile), false);
});

test('the tmp instance used by external-editor rejects prefix/postfix directory escape', () => {
  const attacks = [{ prefix: '../escape-' }, { postfix: '/../escape' }];
  if (path.sep === '\\') attacks.push({ prefix: '..\\escape-' });
  for (const options of attacks) {
    assert.throws(() => tmp.tmpNameSync(options), Error, `Must reject traversal: ${JSON.stringify(options)}`);
  }
  const version = externalRequire('tmp/package.json').version.split('.').map(Number);
  assert.ok(version[0] === 0 && (version[1] > 2 || version[1] === 2 && version[2] >= 6),
    `external-editor must resolve tmp 0.2.6+: ${version.join('.')}`);
});

test('the real cz-customizable prompter retains Chinese questions, limits, skip rules and commit/cancel behavior', async () => {
  const adapter = dependencyRequire('cz-customizable');
  const originalPrompt = inquirer.prompt;
  const originalCwd = process.cwd();
  const commits = [];
  let questions;
  let confirmation = 'yes';
  inquirer.prompt = current => {
    questions = current;
    return Promise.resolve({ type: 'feat', scope: '依赖', subject: '修复依赖安全告警', confirmCommit: confirmation });
  };
  try {
    process.chdir(frontend);
    adapter.prompter(inquirer, message => commits.push(message));
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(commits, ['feat(依赖): 修复依赖安全告警']);
    assert.equal(questions.find(question => question.name === 'type').message, '请选择提交类型:');
    assert.ok(questions.find(question => question.name === 'type').choices.some(choice =>
      choice.value === 'feat' && choice.name.includes('新功能')));
    assert.equal(questions.find(question => question.name === 'scope' && question.type === 'input').message,
      '请输入修改范围(可选):');
    const subject = questions.find(question => question.name === 'subject');
    assert.equal(subject.message, '请简要描述提交(必填):');
    assert.equal(subject.validate('中'.repeat(72)), true);
    assert.notEqual(subject.validate('中'.repeat(73)), true);
    assert.ok(!questions.some(question => ['body', 'footer'].includes(question.name)));
    assert.equal(questions.find(question => question.name === 'confirmCommit').message({
      type: 'feat', scope: '依赖', subject: '修复依赖安全告警'
    }), '确认使用以上信息提交？(y/n/e/h)');
    confirmation = 'no';
    adapter.prompter(inquirer, message => commits.push(message));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(commits.length, 1, 'Cancel must not invoke the commit callback');
  } finally {
    inquirer.prompt = originalPrompt;
    process.chdir(originalCwd);
  }
});
