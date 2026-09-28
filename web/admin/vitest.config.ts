import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 单元测试配置。
 *
 * <p>环境是 {@code node} 而不是 {@code jsdom}：这一层测的是**与框架无关的逻辑**
 * （信封解析、错误码、URL 拼装、游标翻页），而它们恰好是最容易错、也最值得
 * 单独测的部分。组件挂载测的是「模板有没有把字段拼对」，那件事由
 * {@code tools/verify_admin_spa.py} 逐字段对比 Java 记录来保证——
 * 它比「渲染一次再看文本」更早发现漂移（字段名对不上时渲染不会失败）。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.test.ts'],
  },
})
