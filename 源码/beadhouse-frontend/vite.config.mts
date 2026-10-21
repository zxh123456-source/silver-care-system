import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueJsx from '@vitejs/plugin-vue-jsx'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'
import { existsSync, readdirSync } from 'node:fs'

// Resolve real style entries: package export globs also expose type-only paths.
const elementComponents = new URL('../../', import.meta.resolve('element-plus/es/components/base/style/css'))
const elementStyles = readdirSync(elementComponents)
  .filter(name => existsSync(new URL(`${name}/style/css.mjs`, elementComponents)))
  .map(name => `element-plus/es/components/${name}/style/css`)

export default defineConfig({
  plugins: [
    vue(),
    vueJsx(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      components: fileURLToPath(new URL('./src/components', import.meta.url))
    }
  },
  server: {
    host: '127.0.0.1',
    port: 8080,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.SILVERCARE_BACKEND_URL || 'http://127.0.0.1:9001',
        changeOrigin: true,
        rewrite: path => path.replace(/^\/api/, '')
      }
    }
  },
  optimizeDeps: {
    include: elementStyles
  },
  css: {
    preprocessorMaxWorkers: 2,
    preprocessorOptions: {
      scss: {
        fatalDeprecations: ['import', 'global-builtin']
      }
    }
  }
})
