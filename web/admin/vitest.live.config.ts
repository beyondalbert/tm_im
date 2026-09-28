import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vitest/config'

/**
 * 真实服务端的集成测试（需要先起 tm-admin.jar）。
 *
 * <p>它跑的是**线上那段客户端代码**（{@code src/api/}），只是把
 * {@code localStorage} 换成内存实现、把 baseUrl 指向 {@code 127.0.0.1:8081}。
 * 因此它验证的是「前端的字段名/参数名/信封假设与服务端真实行为一致」——
 * 这类漂移在构建、类型检查、单元测试里都不会失败，表现是界面上某个字段
 * 永远空白或某个筛选静默失效。
 *
 * <p>没有 {@code TM_ADMIN_URL} 时整体跳过：默认的 {@code npm test} 不该
 * 要求一个正在运行的服务端。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['tests/live/**/*.test.ts'],
    testTimeout: 30_000,
    hookTimeout: 30_000,
    // 串行执行：所有用例打的是同一个真实服务端，并行会让「审计里多了几行」
    // 这类断言互相干扰。
    fileParallelism: false,
    sequence: { concurrent: false },
  },
})
