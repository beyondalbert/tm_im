import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import 'element-plus/dist/index.css'
import './styles/main.css'

import App from './App.vue'
import { setSessionLostHandler } from '@/api'
import { router } from '@/router'
import { useAuthStore } from '@/stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
// 中文语言包不只是文案：日期/分页的格式也来自它。
app.use(ElementPlus, { locale: zhCn })

/**
 * 「凭证不可用」 → 一次具体的行为：清本地身份 + 回登录页并记住原来想去哪。
 *
 * <p>把它放在这里而不是 client 里，是为了让 client 保持与框架无关
 * （同一份代码在 Node 集成测试里跑）。注意 `replace` 而不是 `push`：
 * 会话失效回登录页之后，用户按「后退」不该回到那个已经拿不到数据的页面。
 */
const auth = useAuthStore(pinia)
setSessionLostHandler(() => {
  auth.forget()
  const from = router.currentRoute.value.fullPath
  void router.replace({ name: 'login', query: from.startsWith('/login') ? {} : { redirect: from } })
})

app.mount('#app')
