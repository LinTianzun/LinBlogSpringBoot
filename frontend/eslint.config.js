import js from '@eslint/js'
import prettierConfig from 'eslint-config-prettier'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import tseslint from 'typescript-eslint'

const vueRecommended = pluginVue.configs['flat/recommended'] ?? pluginVue.configs.recommended

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'components.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...vueRecommended,
  prettierConfig,
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser, sourceType: 'module' },
    },
  },
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
    },
  },
)
