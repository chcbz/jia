# Mocha 单元测试配置

本项目已从 Vitest 迁移到 Mocha 作为单元测试框架。

## 安装的依赖

- `mocha`: 测试框架
- `chai`: 断言库
- `@babel/register`: Babel 转译支持

## 测试脚本

在 `package.json` 中配置了以下测试脚本：

- `npm test`: 运行所有测试
- `npm run test:watch`: 监听模式运行测试
- `npm run test:run`: 运行测试（与 `npm test` 相同）

## 测试文件位置

测试文件位于 `tests/` 目录下，使用 `.spec.js` 或 `.test.js` 扩展名。

## 运行测试

运行所有测试：
```bash
npm test
```

运行特定测试文件：
```bash
npx mocha tests/composables/useHttp.spec.js
```

运行多个测试文件：
```bash
npx mocha tests/basic.test.js tests/composables/useHttp.spec.js
```

## 测试编写示例

```javascript
import { expect } from 'chai'
import { cleanup } from '../setup.js'

describe('测试套件', () => {
  afterEach(() => {
    cleanup() // 清理测试环境
  })

  it('应该通过基本断言', () => {
    expect(true).to.be.true
    expect(1 + 1).to.equal(2)
  })

  it('应该处理对象', () => {
    const obj = { name: 'test' }
    expect(obj).to.have.property('name')
    expect(obj.name).to.equal('test')
  })
})
```

## 注意事项

1. **Vue 组件测试**: 由于 Node.js 无法直接处理 `.vue` 文件，Vue 组件测试需要额外的配置。当前配置主要支持 JavaScript 测试。

2. **ES 模块**: 项目已配置为 ES 模块 (`"type": "module"`)，所有导入需要使用 ES 模块语法。

3. **DOM 环境**: 测试设置中已配置了 JSDOM 环境，支持 DOM 相关的测试。

## 从 Vitest 迁移的更改

1. 移除了 `vitest` 和 `@vitest/ui` 依赖
2. 添加了 `mocha` 和 `chai` 依赖
3. 更新了测试脚本
4. 创建了 Mocha 配置文件 `.mocharc.js`
5. 修改了测试文件中的断言语法
6. 更新了测试设置文件 `tests/setup.js`
7. 添加了 ES 模块支持 (`"type": "module"`)
