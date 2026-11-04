import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import store, { key } from './store'
import SvgIcon from '@/icons'
import './styles/index.scss'
import 'element-plus/dist/index.css'
import '@icon-park/vue-next/styles/index.css'
import './styles/tailwind.css'
import './styles/theme.scss'
import ElementPlus from 'element-plus'
const app = createApp(App)
store.commit('app/setAuth', false)
SvgIcon(app)

app
  .use(store, key)
  .use(router)
  .use(ElementPlus)
  .mount('#app')
