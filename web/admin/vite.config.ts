import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * 管理后台 SPA 的构建配置。
 *
 * <p>两处非默认值都是**契约**，不是偏好：
 *
 * <ol>
 *   <li>{@code base: '/admin/'} —— 后台由 {@code tm-admin.jar} 在
 *       {@code /admin/**} 下提供，管理 API 在 {@code /v1/admin/**}
 *       （DESIGN §5.1）。前端与 API 同源、只差一个前缀，所以生产环境
 *       不需要 CORS；开发期靠下面的 {@code server.proxy} 复现这个同源关系。</li>
 *   <li>{@code server.proxy} 的 {@code '/v1'} —— 代理的是**API**，不是
 *       {@code /admin}。开发时浏览器地址栏是 {@code localhost:5174/admin/...}，
 *       而请求 {@code /v1/admin/...} 被转到 {@code 127.0.0.1:8081}。
 *       若反过来把 API 也放到 {@code /admin} 下，生产环境就会与静态资源
 *       抢同一个前缀，SPA 回退（DESIGN §5.3）会把 {@code GET /admin/actors}
 *       当成路由而不是接口。</li>
 * </ol>
 */
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    // 关掉 host 检查：开发机上用了 hostname 而不是 localhost 时会报 "Blocked request"
    proxy: {
      '/v1': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 后台不发布 sourcemap：它会把内部字段名与代码结构一起送出去，
    // 而排查后台的问题用不到浏览器里的源码映射（服务端有完整日志）。
    sourcemap: false,
    // Element Plus 是**全量引入**的（内部工具，不追求首屏体积），产物约 1MB，
    // 会超过 Vite 默认的 500KB 警告线。把阈值抬到实际值之上，是为了让这个警告
    // 在**真的**发生体积回归时还能起作用——一个永远亮着的警告等于没有警告。
    chunkSizeWarningLimit: 1500,
  },
})
