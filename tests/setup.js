import { JSDOM } from 'jsdom'

// 设置全局的 DOM 环境
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
  url: 'http://localhost'
})

// 在 ES 模块中，我们需要更小心地设置全局属性
Object.defineProperty(global, 'window', { value: dom.window, writable: true })
Object.defineProperty(global, 'document', { value: dom.window.document, writable: true })
Object.defineProperty(global, 'navigator', { value: dom.window.navigator, writable: true })

// 导出 cleanup 函数，让测试文件可以在 afterEach 中使用
export { cleanup } from '@testing-library/vue'
