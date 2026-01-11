import { ref } from 'vue'
import { useApiStore } from '../stores/api'
import { useGlobalStore } from '../stores/global'
import { useUtilStore } from '../stores/util'

/**
 * 创建超时信号，兼容不支持 AbortSignal.timeout() 的浏览器
 * @param {number} milliseconds - 超时时间（毫秒）
 * @returns {Object} 包含 signal 和 cleanup 函数的对象
 */
function createTimeoutSignal (milliseconds) {
  // 如果浏览器原生支持 AbortSignal.timeout，直接使用
  if (typeof AbortSignal !== 'undefined' && AbortSignal.timeout) {
    const signal = AbortSignal.timeout(milliseconds)
    return {
      signal,
      cleanup: () => {} // 原生实现不需要清理
    }
  }

  // 否则使用 AbortController 和 setTimeout 实现
  const controller = new AbortController()
  const timeoutId = setTimeout(() => {
    controller.abort(new DOMException(`Request timed out after ${milliseconds}ms`, 'AbortError'))
  }, milliseconds)

  // 清理定时器，避免内存泄漏
  const signal = controller.signal
  const cleanup = () => {
    clearTimeout(timeoutId)
  }

  // 当信号被中止时，也清除定时器（作为后备）
  signal.addEventListener('abort', cleanup, { once: true })

  return { signal, cleanup }
}

/**
 * 可复用的 HTTP 请求组合式函数
 * @param {Object} options - 配置选项
 * @param {string} options.url - 请求URL（相对路径）
 * @param {string} options.method - HTTP方法，默认为 'GET'
 * @param {Object} options.data - 请求数据
 * @param {Object} options.params - URL参数
 * @param {Object} options.headers - 自定义请求头
 * @param {boolean} options.autoLoading - 是否自动管理loading状态，默认为true
 * @param {boolean} options.needAuth - 是否需要认证，默认为true
 * @param {string} options.responseType - 响应类型，支持 'json'（默认）和 'stream'（流式响应）
 * @param {Function} options.onSuccess - 成功回调
 * @param {Function} options.onError - 错误回调
 * @param {Function} options.onFinally - 最终回调
 * @param {Function} options.onStream - 流式数据回调（当 responseType 为 'stream' 时使用）
 * @param {Function} options.onStreamEnd - 流式结束回调（当 responseType 为 'stream' 时使用）
 * @returns {Object} 包含执行函数和响应式状态的组合
 */
export function useHttp (options = {}) {
  const loading = ref(false)
  const error = ref(null)
  const data = ref(null)
  const response = ref(null)

  const apiStore = useApiStore()
  const globalStore = useGlobalStore()
  const utilStore = useUtilStore()

  const defaultOptions = {
    method: 'GET',
    autoLoading: true,
    needAuth: true,
    headers: {}
  }

  const execute = async (executeOptions = {}) => {
    const mergedOptions = { ...defaultOptions, ...options, ...executeOptions }

    const {
      url,
      method,
      data: requestData,
      params,
      headers: customHeaders,
      autoLoading,
      needAuth,
      responseType = 'json',
      onSuccess,
      onError,
      onFinally,
      onStream,
      onStreamEnd
    } = mergedOptions

    if (autoLoading) {
      loading.value = true
    }
    error.value = null

    // 声明 timeoutSignal 变量，使其在 finally 块中可访问
    let timeoutSignal = null

    try {
      // 准备请求配置
      const headers = { ...customHeaders }
      if (!headers['Content-Type']) {
        headers['Content-Type'] = 'application/json'
      }

      const config = {
        method: method.toUpperCase(),
        headers
      }

      // 添加请求数据
      if (requestData && ['POST', 'PUT', 'PATCH'].includes(config.method)) {
        config.body = JSON.stringify(requestData)
      }

      // 添加URL参数和baseURL
      const baseURL = import.meta.env.VITE_API_BASE_URL || ''
      let requestUrl = url

      // 如果URL不是绝对路径，添加baseURL
      if (!url.startsWith('http://') && !url.startsWith('https://') && baseURL) {
        requestUrl = `${baseURL}${url.startsWith('/') ? url : `/${url}`}`
      }

      if (params && Object.keys(params).length > 0) {
        const urlParams = new URLSearchParams(params).toString()
        requestUrl = `${requestUrl}${requestUrl.includes('?') ? '&' : '?'}${urlParams}`
      }

      // 如果需要认证，获取token
      let token = null
      if (needAuth) {
        try {
          token = await apiStore.token()
          if (token) {
            config.headers.Authorization = `Bearer ${token}`
          }
        } catch (authError) {
          throw new Error(`Authentication failed: ${authError.message}`)
        }
      }

      // 使用 fetch API 替代 axios，特别是为了支持 stream
      timeoutSignal = createTimeoutSignal(180000) // 180秒超时
      const fetchConfig = {
        method: config.method,
        headers: config.headers,
        body: config.body,
        signal: timeoutSignal.signal
      }

      // 对于 GET 请求，不要包含 body
      if (config.method === 'GET') {
        delete fetchConfig.body
      }

      const response = await fetch(requestUrl, fetchConfig)

      // 检查 HTTP 错误状态（fetch 不会自动抛出非 2xx 的错误）
      if (!response.ok) {
        let errorMessage = `HTTP error! status: ${response.status}`

        // 尝试从响应中提取错误消息
        try {
          const errorData = await response.clone().json()
          if (errorData && errorData.msg) {
            errorMessage = errorData.msg
          } else if (errorData && errorData.message) {
            errorMessage = errorData.message
          }
        } catch (jsonError) {
          // 如果无法解析为 JSON，使用默认错误消息
        }

        const error = new Error(errorMessage)
        error.status = response.status
        error.response = response
        throw error
      }

      // 处理流式响应
      if (responseType === 'stream') {
        if (!response.body) {
          throw new Error('Response body is not available for streaming')
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        try {
          while (true) {
            const { done, value } = await reader.read()
            if (done) {
              // 处理缓冲区中剩余的数据
              if (buffer.trim() && onStream) {
                onStream(buffer)
              }
              if (onStreamEnd) {
                onStreamEnd()
              }
              break
            }

            const chunk = decoder.decode(value, { stream: true })
            buffer += chunk

            // 处理 SSE (Server-Sent Events) 格式或其他流式数据
            // 查找完整的事件（以换行符分隔）
            let eventEndIndex
            while ((eventEndIndex = buffer.indexOf('\n')) !== -1) {
              const event = buffer.substring(0, eventEndIndex).trim()
              buffer = buffer.substring(eventEndIndex + 1)

              if (event) {
                if (onStream) {
                  onStream(event)
                }
              }
            }
          }
        } catch (streamError) {
          if (onError) {
            onError(streamError.message, streamError)
          }
          throw streamError
        } finally {
          try {
            await reader.cancel()
          } catch (cancelError) {
            console.warn('Failed to cancel stream reader:', cancelError)
          }
        }

        // 对于流式响应，返回包含流信息的对象
        return {
          data: null,
          status: response.status,
          statusText: response.statusText,
          headers: Object.fromEntries(response.headers.entries()),
          config: fetchConfig,
          stream: {
            reader,
            cancel: () => reader.cancel()
          }
        }
      }

      // 处理普通 JSON 响应
      const result = await response.json()
      const resultObj = {
        data: result,
        status: response.status,
        statusText: response.statusText,
        headers: Object.fromEntries(response.headers.entries()),
        config: fetchConfig
      }

      response.value = resultObj
      data.value = result

      if (onSuccess) {
        onSuccess(result, resultObj)
      }

      return resultObj

    } catch (err) {
      error.value = err.message || '请求失败'

      // 处理认证错误（fetch 的错误结构不同）
      if (err.name === 'AbortError') {
        error.value = '请求超时'
      } else if (err.status === 401 && needAuth) {
        apiStore.cleanToken()
        console.warn('Authentication expired, token cleaned')
      }

      if (onError) {
        onError(error.value, err)
      }

      throw err

    } finally {
      // 清理超时定时器
      if (timeoutSignal && timeoutSignal.cleanup) {
        timeoutSignal.cleanup()
      }
      
      if (autoLoading) {
        loading.value = false
      }
      if (onFinally) {
        onFinally()
      }
    }
  }

  // 快捷方法
  const get = (url, options = {}) => execute({ ...options, url, method: 'GET' })
  const post = (url, data, options = {}) => execute({ ...options, url, method: 'POST', data })
  const put = (url, data, options = {}) => execute({ ...options, url, method: 'PUT', data })
  const patch = (url, data, options = {}) => execute({ ...options, url, method: 'PATCH', data })
  const del = (url, options = {}) => execute({ ...options, url, method: 'DELETE' });

  return {
    // 响应式状态
    loading,
    error,
    data,
    response,

    // 执行方法
    execute,

    // 快捷方法
    get,
    post,
    put,
    patch,
    delete: del,

    // 重置状态
    reset: () => {
      loading.value = false
      error.value = null
      data.value = null
      response.value = null
    }
  }
}

/**
 * 创建特定API端点的快捷方式
 * @param {string} basePath - API基础路径
 * @returns {Object} 包含CRUD操作的对象
 */
export function createApi (basePath) {
  return {
    list: (uri, data, options = {}) =>
      useHttp().post(`${basePath}${uri}`, data, options),

    get: (uri, id, options = {}) =>
      useHttp().get(`${basePath}${uri}?id=${id}`, options),

    create: (uri, data, options = {}) =>
      useHttp().post(`${basePath}${uri}`, data, options),

    update: (uri, data, options = {}) =>
      useHttp().post(`${basePath}${uri}`, data, options),

    patch: (uri, id, data, options = {}) =>
      useHttp().patch(`${basePath}${uri}/${id}`, data, options),

    delete: (uri, id, options = {}) =>
      useHttp().delete(`${basePath}${uri}?id=${id}`, options),

    search: (uri, data, options = {}) =>
      useHttp().post(`${basePath}${uri}/search`, data, options)
  }
}

// 预定义的API端点
export const taskApi = createApi('/task')
export const phraseApi = createApi('/phrase')
export const userApi = createApi('/user')
export const voteApi = createApi('/vote')
export const tipApi = createApi('/tip')
export const kefuApi = createApi('/kefu')
export const mcpApi = createApi('/mcp')
