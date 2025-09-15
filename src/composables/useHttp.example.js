/**
 * useHttp 组合式函数使用示例
 * 这个文件展示了如何使用新创建的 HTTP 请求封装
 */

import { ref } from 'vue'
import { useHttp, taskApi, phraseApi } from './useHttp'

// 示例 1: 基本用法
export function useTaskList () {
  const { loading, error, data, execute } = useHttp({
    url: '/task/search',
    method: 'POST',
    autoLoading: true
  })

  const loadTasks = async (searchParams = {}) => {
    try {
      const result = await execute({
        data: { search: searchParams }
      })
      console.log('Tasks loaded:', result.data)
      return result.data
    } catch (err) {
      console.error('Failed to load tasks:', err)
      throw err
    }
  }

  return {
    loading,
    error,
    tasks: data,
    loadTasks
  }
}

// 示例 2: 使用预定义的API端点
export function usePhraseOperations () {
  const {
    loading: phraseLoading,
    error: phraseError,
    data: phraseData,
    execute: getRandomPhrase
  } = useHttp()

  const {
    loading: voteLoading,
    error: voteError,
    execute: submitVote
  } = useHttp()

  const loadRandomPhrase = async () => {
    try {
      const result = await phraseApi.get('random', {
        data: { jiacn: globalStore.getJiacn }
      })
      return result.data
    } catch (err) {
      console.error('Failed to load phrase:', err)
      throw err
    }
  }

  const voteForPhrase = async (phraseId, voteType) => {
    try {
      const result = await phraseApi.post('vote', {
        jiacn: globalStore.getJiacn,
        phraseId,
        vote: voteType
      })
      return result.data
    } catch (err) {
      console.error('Failed to vote:', err)
      throw err
    }
  }

  return {
    phraseLoading,
    phraseError,
    phraseData,
    voteLoading,
    voteError,
    loadRandomPhrase,
    voteForPhrase
  }
}

// 示例 3: 在Vue组件中使用
/*
<template>
  <div>
    <button @click="loadData" :disabled="loading">
      {{ loading ? 'Loading...' : 'Load Data' }}
    </button>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="data">
      <pre>{{ JSON.stringify(data, null, 2) }}</pre>
    </div>
  </div>
</template>

<script>
import { useHttp } from '../composables/useHttp'

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
</script>
*/

// 示例 4: 带回调的用法
export function useUserProfile () {
  const { loading, error, data, execute } = useHttp({
    url: '/user/profile',
    method: 'GET',
    onSuccess: (responseData) => {
      console.log('Profile loaded successfully:', responseData)
      // 可以在这里更新全局状态或其他操作
    },
    onError: (errorMessage, error) => {
      console.error('Failed to load profile:', errorMessage)
      // 可以在这里显示错误提示
    },
    onFinally: () => {
      console.log('Profile request completed')
    }
  })

  const loadProfile = () => execute()

  return {
    loading,
    error,
    profile: data,
    loadProfile
  }
}

// 示例 5: 不需要认证的请求
export function usePublicData () {
  const { loading, error, data, execute } = useHttp({
    url: '/public/data',
    method: 'GET',
    needAuth: false // 不需要认证
  })

  const loadPublicData = () => execute()

  return {
    loading,
    error,
    publicData: data,
    loadPublicData
  }
}

// 示例 6: 文件上传
export function useFileUpload () {
  const { loading, error, data, execute } = useHttp()

  const uploadFile = async (file, additionalData = {}) => {
    const formData = new FormData()
    formData.append('file', file)

    // 添加其他数据
    Object.keys(additionalData).forEach(key => {
      formData.append(key, additionalData[key])
    })

    try {
      const result = await execute({
        url: '/upload',
        method: 'POST',
        data: formData,
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      })
      return result.data
    } catch (err) {
      console.error('Upload failed:', err)
      throw err
    }
  }

  return {
    loading,
    error,
    uploadResult: data,
    uploadFile
  }
}

// 示例 7: 流式响应 (Server-Sent Events)
export function useStreamingChat () {
  const { loading, error, data, execute } = useHttp()
  const messages = ref([])
  const currentMessage = ref('')

  const sendMessage = async (message) => {
    try {
      currentMessage.value = ''
      messages.value.push({ role: 'user', content: message })

      await execute({
        url: '/chat/stream',
        method: 'POST',
        data: { message },
        responseType: 'stream',
        autoLoading: false, // 流式请求通常不需要自动loading
        onStream: (eventData) => {
          // 处理 SSE 事件数据
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
            const messageContent = data.content || data.v || ''
            if (messageContent) {
              currentMessage.value += messageContent
            }
          } catch {
            currentMessage.value += content
          }
        },
        onStreamEnd: () => {
          // 流式响应结束，将当前消息添加到消息列表
          if (currentMessage.value) {
            messages.value.push({ role: 'assistant', content: currentMessage.value })
            currentMessage.value = ''
          }
        },
        onError: (errorMessage) => {
          console.error('Stream error:', errorMessage)
          messages.value.push({ role: 'system', content: `Error: ${errorMessage}`, isError: true })
        }
      })
    } catch (err) {
      console.error('Failed to send message:', err)
      messages.value.push({ role: 'system', content: 'Failed to send message', isError: true })
    }
  }

  return {
    loading,
    error,
    messages,
    currentMessage,
    sendMessage
  }
}

// 示例 8: 在Vue组件中使用流式响应
/*
<template>
  <div class="chat-container">
    <div class="messages">
      <div v-for="(msg, index) in messages" :key="index" :class="['message', msg.role]">
        {{ msg.content }}
      </div>
      <div v-if="currentMessage" class="message assistant streaming">
        {{ currentMessage }}
      </div>
    </div>

    <div class="input-area">
      <input v-model="inputMessage" @keyup.enter="send" placeholder="Type a message...">
      <button @click="send" :disabled="loading">Send</button>
    </div>
  </div>
</template>

<script>
import { ref } from 'vue'
import { useStreamingChat } from '../composables/useHttp.example'

export default {
  setup() {
    const inputMessage = ref('')
    const { loading, messages, currentMessage, sendMessage } = useStreamingChat()

    const send = async () => {
      if (!inputMessage.value.trim()) return
      await sendMessage(inputMessage.value)
      inputMessage.value = ''
    }

    return {
      inputMessage,
      loading,
      messages,
      currentMessage,
      send
    }
  }
}
</script>

<style>
.chat-container {
  max-width: 600px;
  margin: 0 auto;
  padding: 20px;
}

.messages {
  margin-bottom: 20px;
}

.message {
  padding: 10px;
  margin: 5px 0;
  border-radius: 8px;
}

.message.user {
  background: #e3f2fd;
  margin-left: 20%;
}

.message.assistant {
  background: #f5f5f5;
  margin-right: 20%;
}

.message.streaming {
  animation: pulse 1.5s infinite;
}

@keyframes pulse {
  0% { opacity: 1; }
  50% { opacity: 0.7; }
  100% { opacity: 1; }
}

.input-area {
  display: flex;
  gap: 10px;
}

.input-area input {
  flex: 1;
  padding: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
}

.input-area button {
  padding: 8px 16px;
  background: #1976d2;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.input-area button:disabled {
  background: #ccc;
  cursor: not-allowed;
}
</style>
*/
