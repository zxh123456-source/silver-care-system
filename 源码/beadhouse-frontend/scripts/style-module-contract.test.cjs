const assert = require('node:assert/strict');
const { test } = require('node:test');
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const sass = require('sass');
const postcss = require('postcss');
const { parse, compileStyle } = require('@vue/compiler-sfc');

const frontend = path.resolve(__dirname, '..');
const options = { fatalDeprecations: ['import', 'global-builtin'] };

test('the global Sass entry compiles without deprecated APIs and retains sidebar geometry', () => {
  const result = sass.compile(path.join(frontend, 'src/styles/index.scss'), options);
  const root = postcss.parse(result.css);
  const widths = new Set();
  root.walkDecls('width', decl => widths.add(decl.value));
  assert.ok(widths.has('210px') && widths.has('54px'));
  assert.ok(widths.has('calc(100% - 210px)') && widths.has('calc(100% - 54px)'));
  assert.ok(result.css.includes('#031429') && result.css.includes('#558df6'));
});

test('the JavaScript CSS-module contract retains its exported menu tokens', () => {
  const root = postcss.parse(sass.compile(path.join(frontend, 'src/styles/variables.module.scss'), options).css);
  const exports = {};
  root.walkRules(':export', rule => rule.walkDecls(decl => { exports[decl.prop] = decl.value; }));
  assert.equal(exports.sideBarWidth, '210px');
  assert.equal(exports.hideSideBarWidth, '54px');
  assert.equal(exports.menuBg, '#031429');
  assert.equal(exports.menuHover, '#558df6');
  assert.equal(Object.keys(exports).length, 8);
});

test('component Sass styles compile independently without implicit global CSS injection', () => {
  let checked = 0;
  for (const filename of fs.readdirSync(path.join(frontend, 'src'), { recursive: true })) {
    if (!filename.endsWith('.vue')) continue;
    const absolute = path.join(frontend, 'src', filename);
    const { descriptor, errors } = parse(fs.readFileSync(absolute, 'utf8'), { filename: absolute });
    assert.deepEqual(errors, []);
    for (const style of descriptor.styles.filter(style => style.lang === 'scss')) {
      sass.compileString(style.content, { ...options, url: pathToFileURL(absolute) });
      checked++;
    }
  }
  assert.ok(checked > 20, 'Representative component styles must actually execute');
});

test('authentication deep selectors compile without deprecation and still reach input append controls', () => {
  const warnings = [];
  const warn = console.warn;
  try {
    console.warn = (...args) => warnings.push(args.join(' '));
    for (const component of ['LoginModel.vue', 'ForgetPassModel.vue']) {
      const filename = path.join(frontend, 'src/views/login/components', component);
      const { descriptor } = parse(fs.readFileSync(filename, 'utf8'), { filename });
      for (const style of descriptor.styles) {
        const source = sass.compileString(style.content, options).css;
        const result = compileStyle({ source, filename, scoped: true, id: 'data-v-login-contract' });
        assert.deepEqual(result.errors, []);
        assert.match(result.code, /\[data-v-login-contract\] \.el-input-group__append/);
      }
    }
    assert.deepEqual(warnings, []);
  } finally {
    console.warn = warn;
  }
});
