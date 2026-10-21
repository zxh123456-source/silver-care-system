const js = require('@eslint/js')
const vue = require('eslint-plugin-vue')
const ts = require('typescript-eslint')
const globals = require('globals')

module.exports = ts.config(
  { ignores: ['dist/**', 'node_modules/**', 'auto-imports.d.ts', 'components.d.ts'] },
  js.configs.recommended,
  ...ts.configs.recommended,
  ...vue.configs['flat/essential'],
  {
    files: ['src/**/*.{js,ts,vue}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
      parserOptions: { parser: ts.parser, ecmaFeatures: { jsx: true }, extraFileExtensions: ['.vue'] }
    },
    rules: {
      'no-console': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
      'no-debugger': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
      'vue/multi-word-component-names': 'off',
      'vue/no-mutating-props': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-expressions': ['error', { allowShortCircuit: true, allowTernary: true }],
      '@typescript-eslint/no-namespace': ['error', { allowDeclarations: true }],
      '@typescript-eslint/no-unused-vars': 'warn'
    }
  }
)
