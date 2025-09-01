# HTTP 请求组合式函数

这个目录包含了可复用的 HTTP 请求组合式函数，用于简化 Vue 3 组件中的 HTTP 请求处理。

## 主要功能

- ✅ 自动管理 loading 状态
- ✅ 统一的错误处理
- ✅ 自动认证 token 管理
- ✅ 响应式数据状态
- ✅ 预定义的 API 端点快捷方式
- ✅ 支持回调函数
- ✅ TypeScript 友好
- ✅ 流式响应支持 (Server-Sent Events)

## 安装和使用

### 基本用法

```javascript
import { useHttp } from './composables/useHttp'

// 在组件 setup 中使用
export default {
  setup() {
    const { loading, error, data, execute } = useHttp({
      url: '/api/data',
      method: 'GET'
    })

    const loadData = async () => {
      try {
        await execute()
      } catch (err) {
        console.error('Failed to load data:', err)
      }
    }

    return {
      loading,
      error,
      data,
      loadData
    }
  }
}
```

### 使用预定义的 API 端点

```javascript
import { taskApi, phraseApi } from './composables/useHttp'

// 任务相关操作
const loadTasks = async () => {
  const result = await taskApi.list({ status: 1 })
  return result.data
}

const createTask = async (taskData) => {
  const result = await taskApi.create(taskData)
  return result.data
}

// 短语相关操作
const getRandomPhrase = async () => {
  const result = await phraseApi.get('random', {
    data: { jiacn: 'user123' }
  })
  return result.data
}
```

## API 参考

### useHttp(options)

创建 HTTP 请求组合式函数。

**参数:**
- `options.url` (string): 请求 URL（相对路径）
- `options.method` (string): HTTP 方法，默认为 'GET'
- `options.data` (Object): 请求数据
- `options.params` (Object): URL 参数
- `options.headers` (Object): 自定义请求头
- `options.autoLoading` (boolean): 是否自动管理 loading 状态，默认为 true
- `options.needAuth` (boolean): 是否需要认证，默认为 true
- `options.responseType` (string): 响应类型，支持 'json'（默认）和 'stream'（流式响应）
- `options.onSuccess` (Function): 成功回调
- `options.onError` (Function): 错误回调
- `options.onFinally` (Function): 最终回调
- `options.onStream` (Function): 流式数据回调（当 responseType 为 'stream' 时使用）
- `options.onStreamEnd` (Function): 流式结束回调（当 responseType 为 'stream' 时使用）

**返回值:**
- `loading` (Ref<boolean>): 加载状态
- `error` (Ref<string|null>): 错误信息
- `data` (Ref<any>): 响应数据
- `response` (Ref<any>): 完整响应对象
- `execute()`: 执行请求的方法
- `get()`, `post()`, `put()`, `patch()`, `delete()`: 快捷方法
- `reset()`: 重置状态的方法

### createApi(basePath)

创建特定 API 端点的快捷方式。

**参数:**
- `basePath` (string): API 基础路径

**返回值:**
包含 CRUD 操作方法的对象：
- `list(params, options)`: 获取列表
- `get(id, options)`: 获取单个项目
- `create(data, options)`: 创建项目
- `update(id, data, options)`: 更新项目
- `patch(id, data, options)`: 部分更新项目
- `delete(id, options)`: 删除项目
- `search(data, options)`: 搜索项目

## 流式响应使用指南

useHttp 现在支持 Server-Sent Events (SSE) 流式响应，适用于实时聊天、实时数据更新等场景。

### 基本用法

```javascript
import { useHttp } from './composables/useHttp'

const { loading, error, execute } = useHttp()

const startStream = async () => {
  await execute({
    url: '/chat/stream',
    method: 'POST',
    data: { message: 'Hello' },
    responseType: 'stream',
    autoLoading: false, // 流式请求通常不需要自动loading
    onStream: (eventData) => {
      // 处理 SSE 事件数据
      console.log('Received stream data:', eventData)
      
      // 解析 SSE 格式
      let content = ''
      for (const line of eventData.split(/\n/)) {
        if (line.startsWith('data:')) {
          content += line.slice(5).trim() + '\n'
        }
      }
      content = content.trim()
      
      if (content === '[DONE]' || content === '[EOM]') {
        return
      }

      try {
        const data = JSON.parse(content)
        console.log('Parsed data:', data)
      } catch {
        console.log('Raw content:', content)
      }
    },
    onStreamEnd: () => {
      console.log('Stream ended')
    },
    onError: (errorMessage) => {
      console.error('Stream error:', errorMessage)
    }
  })
}
```

### 在 Vue 组件中使用

```javascript
import { ref } from 'vue'
import { useHttp } from './composables/useHttp'

export default {
  setup() {
    const messages = ref([])
    const currentMessage = ref('')
    
    const { loading, execute } = useHttp()

    const sendMessage = async (message) => {
      currentMessage.value = ''
      messages.value.push({ role: 'user', content: message })

      await execute({
        url: '/chat/stream',
        method: 'POST',
        data: { message },
        responseType: 'stream',
        autoLoading: false,
        onStream: (eventData) => {
          // 处理流式数据
          let content = ''
          for (const line of eventData.split(/\n/)) {
            if (line.startsWith('data:')) {
              content += line.slice(5).trim() + '\n'
            }
          }
          content = content.trim()
          
          if (content && content !== '[DONE]' && content !== '[EOM]') {
            try {
              const data = JSON.parse(content)
              currentMessage.value += data.content || data.v || ''
            } catch {
              currentMessage.value += content
            }
          }
        },
        onStreamEnd: () => {
          if (currentMessage.value) {
            messages.value.push({ role: 'assistant', content: currentMessage.value })
            currentMessage.value = ''
          }
        }
      })
    }

    return {
      messages,
      currentMessage,
      loading,
      sendMessage
    }
  }
}
```

## 预定义的 API 端点

- `taskApi`: `/task` 端点操作
- `phraseApi`: `/phrase` 端点操作  
- `userApi`: `/user` 端点操作
- `voteApi`: `/vote` 端点操作
- `tipApi`: `/tip` 端点操作
- `kefuApi`: `/kefu` 端点操作

## 迁移指南

### 旧代码示例

```javascript
// 之前的方式
this.$http.post(baseUrl + '/task/search', {
  search: {
    jiacn: jiacn,
    status: 1
  }
}).then(res => {
  this.list = res.data.data
})
```

### 新代码示例

```javascript
// 新的方式 - 使用组合式函数
import { useHttp } from '../composables/useHttp'

const { loading, error, data: tasks, execute } = useHttp()

const loadTasks = async () => {
  await execute({
    url: '/task/search',
    method: 'POST',
    data: {
      search: {
        jiacn: globalStore.getJiacn,
        status: 1
      }
    }
  })
}

// 或者使用预定义的API端点
import { taskApi } from '../composables/useHttp'

const loadTasks = async () => {
  const result = await taskApi.search({
    jiacn: globalStore.getJiacn,
    status: 1
  })
  return result.data
}
```

## 优势

1. **代码复用**: 减少重复的 HTTP 请求代码
2. **状态管理**: 自动管理 loading、error、data 状态
3. **类型安全**: 更好的 TypeScript 支持
4. **可测试性**: 更容易进行单元测试
5. **一致性**: 统一的错误处理和认证逻辑
6. **可维护性**: 集中管理 HTTP 请求逻辑

## 注意事项

- 确保在 main.js 中正确配置了 axios
- 需要安装并配置 Pinia 用于状态管理
- 自动处理 401 错误并清理 token
- 支持自定义请求头和内容类型

## 示例

查看 `useHttp.example.js` 文件获取完整的使用示例。
