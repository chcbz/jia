<template>
  <div class="chat-container">
    <div class="chat-main-content">
      <div class="chat-messages" ref="messagesRef">
        <div v-if="shouldShowEmptyState" class="chat-empty-state">
          <p>开始与JiA智能助手对话吧！</p>
          <p class="chat-empty-hint">输入您的问题或想法，我将尽力为您解答</p>
        </div>
        <div
          v-for="(msg, index) in messages"
          :key="index"
          :class="[
            'chat-message',
            msg.sender === 'user' ? 'chat-user-message' : 'chat-bot-message'
          ]"
          v-html="DOMPurify.sanitize(marked(msg.content))"
        ></div>
      </div>
      <div class="chat-input">
        <var-input
          v-model="inputMessage"
          @keyup.enter="handleSendOrCancel"
          placeholder="给我发消息"
          textarea
          rows="3"
        />
        <var-button
          @click="handleSendOrCancel"
          :disabled="isSendButtonDisabled"
          type="success"
          round
          icon-container
        >
          <var-icon :name="isStreaming ? 'close' : 'chevron-up'" class="send-icon" />
        </var-button>
      </div>
    </div>

    <div :class="['chat-overlay', { show: showSidebar }]" @click="toggleSidebar"></div>
    <div :class="['chat-sidebar', { show: showSidebar }]">
      <div class="chat-sidebar-header">
        <h3>历史会话</h3>
        <div>
          <var-button
            @click="generateNewConversationId"
            type="primary"
            class="chat-new-conversation-btn"
            >+ 新会话</var-button
          >
          <!-- <var-button @click="toggleSidebar" class="chat-close-btn" type="default">×</var-button> -->
        </div>
      </div>
      <div class="chat-conversation-list">
        <div
          v-for="conv in conversations"
          :key="conv.id"
          :class="['chat-conversation-item', { active: conv.id === conversationId }]"
          @click="loadConversation(conv.id)"
        >
          <div class="chat-conversation-content">
            <div class="chat-conversation-title">
              {{ conv.title || '新会话' }}
            </div>
            <div class="chat-conversation-date">
              {{ utilStore.formatDate(conv.lastUpdated) }}
            </div>
          </div>
          <var-button
            class="chat-delete-btn"
            @click.stop.prevent="deleteConversation(conv.id)"
            type="danger"
            >删除</var-button
          >
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { ref, onMounted, computed, watch, getCurrentInstance } from 'vue';
import { useUtilStore } from '../stores/util';
import { useGlobalStore } from '../stores/global';
import { useApiStore } from '../stores/api';
import { useI18n } from 'vue-i18n';
import { useHttp, mcpApi } from '../composables/useHttp';

// 配置marked
marked.setOptions({
  breaks: true,
  gfm: true,
  headerIds: false,
  sanitize: false // 禁用marked内置的sanitize，使用DOMPurify
});

// 错误类型常量
const ERROR_TYPES = {
  NETWORK: 'network',
  SERVER: 'server',
  VALIDATION: 'validation'
};

// 消息类型常量
const MESSAGE_TYPES = {
  USER: 'user',
  BOT: 'bot',
  SYSTEM: 'system'
};

// 响应式状态
const messages = ref([]);
const inputMessage = ref('');
const messagesRef = ref(null);
const isLoading = ref(false);
const isStreaming = ref(false);
const readerRef = ref(null);
const error = ref(null);
const conversationId = ref(Date.now().toString());
const conversations = ref([]);
const showSidebar = ref(false);

// 存储键和工具函数
const STORAGE_KEY = 'chat_conversations';
const utilStore = useUtilStore();
const globalStore = useGlobalStore();
const apiStore = useApiStore();
const { t } = useI18n();

// 计算属性
const hasMessages = computed(() => messages.value.length > 0);
const isSendButtonDisabled = computed(() => isLoading.value || !inputMessage.value.trim());
const sortedConversations = computed(() =>
  [...conversations.value].sort((a, b) => new Date(b.lastUpdated) - new Date(a.lastUpdated))
);
const shouldShowEmptyState = computed(() => !hasMessages.value && !isLoading.value);

// 初始化
const initializeApp = () => {
  globalStore.setTitle(t('chat.title'));
  globalStore.setShowBack(false);
  globalStore.setShowMore(true);

  loadConversations();
};

// 会话管理函数
const loadConversations = () => {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved) {
    conversations.value = JSON.parse(saved);
  }
};

const saveConversation = () => {
  const title = messages.value.find((m) => m.sender === 'user')?.content || '新会话';
  const existingIndex = conversations.value.findIndex((c) => c.id === conversationId.value);

  const conversation = {
    id: conversationId.value,
    title: title.substring(0, 30),
    lastUpdated: new Date().toISOString(),
    messages: [...messages.value] // 创建副本避免引用问题
  };

  if (existingIndex >= 0) {
    conversations.value[existingIndex] = conversation;
  } else {
    conversations.value.unshift(conversation);
  }

  // 只保留最近的20个会话
  if (conversations.value.length > 20) {
    conversations.value = conversations.value.slice(0, 20);
  }

  localStorage.setItem(STORAGE_KEY, JSON.stringify(conversations.value));
};

const loadConversation = (id) => {
  const conversation = conversations.value.find((c) => c.id === id);
  if (conversation) {
    conversationId.value = id;
    messages.value = [...(conversation.messages || [])]; // 创建副本
  }
};

const generateNewConversationId = () => {
  saveConversation();
  conversationId.value = Date.now().toString();
  messages.value = [];
};

// 消息处理函数
const updateBotMessage = (content) => {
  const lastMessage = messages.value[messages.value.length - 1];
  if (lastMessage?.sender === 'bot') {
    lastMessage.content += content;
  } else {
    messages.value.push({
      sender: 'bot',
      content,
      timestamp: new Date().toISOString()
    });
  }
  scrollToBottom();
};

const processBotResponse = (eventData) => {
  console.log('Received event data:', eventData);

  // 处理多种可能的数据格式
  let payload = '';

  // 如果是SSE格式 (data: {...})
  if (eventData.startsWith('data:')) {
    for (const line of eventData.split(/\n/)) {
      if (line.startsWith('data:')) {
        payload += line.slice(5).trim() + '\n';
      }
    }
  } else {
    // 如果不是SSE格式，直接使用原始数据
    payload = eventData;
  }

  payload = payload.trim();

  if (payload === '[DONE]' || payload === '[EOM]' || !payload) {
    console.log('Stream completed or empty payload');
    return;
  }

  try {
    // 尝试解析为JSON
    const data = JSON.parse(payload);
    const messageContent = data.v || data.content || data.message || data.text || '';
    if (messageContent) {
      updateBotMessage(messageContent);
    } else {
      console.log('No message content found in JSON:', data);
    }
  } catch (error) {
    console.log('Not JSON, treating as plain text:', payload);
    // 如果不是JSON，直接作为文本显示
    updateBotMessage(payload);
  }
};

// 消息发送和流处理
const sendMessage = async () => {
  const message = inputMessage.value.trim();
  if (!message || isLoading.value) return;

  isLoading.value = true;
  isStreaming.value = true;
  inputMessage.value = '';

  try {
    // 添加用户消息
    messages.value = [
      ...messages.value,
      {
        sender: 'user',
        content: message,
        timestamp: new Date().toISOString(),
        conversationId: conversationId.value
      }
    ];

    scrollToBottom();

    // 使用新的 useHttp 流式功能
    const result = await mcpApi.create(
      '/chat/stream',
      {
        content: message,
        conversationId: conversationId.value
      },
      {
        responseType: 'stream',
        autoLoading: false,
        onStream: (eventData) => {
          console.log('Stream data received:', eventData);
          processBotResponse(eventData);
        },
        onStreamEnd: () => {
          console.log('Stream ended');
          isStreaming.value = false;
          isLoading.value = false;
          readerRef.value = null;
          saveConversation();
        },
        onError: (errorMessage) => {
          console.error('发送消息失败:', errorMessage);
          throw new Error(errorMessage);
        }
      }
    );

    // 保存reader引用以便后续取消
    if (result && result.stream) {
      readerRef.value = result.stream.reader;
    }
  } catch (err) {
    console.error('发送消息失败:', err);
    isStreaming.value = false;
    isLoading.value = false;
    error.value = '发送消息失败，请重试';
    messages.value = [
      ...messages.value,
      {
        sender: 'system',
        content: '消息发送失败',
        isError: true,
        timestamp: new Date().toISOString()
      }
    ];
  }
};

const stopStream = async () => {
  if (readerRef.value) {
    try {
      await readerRef.value.cancel();
      messages.value = [
        ...messages.value,
        {
          sender: 'system',
          content: '已取消当前请求',
          isInfo: true,
          timestamp: new Date().toISOString()
        }
      ];
    } catch (err) {
      console.error('取消请求失败:', err);
    } finally {
      isStreaming.value = false;
      isLoading.value = false;
      readerRef.value = null;
    }
  }
};

// UI 交互函数
const scrollToBottom = () => {
  requestAnimationFrame(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
    }
  });
};

const handleSendOrCancel = () => {
  if (isStreaming.value) {
    stopStream();
  } else {
    sendMessage();
  }
};

const toggleSidebar = () => {
  globalStore.toggleRightSidebar();
};

// 删除会话（带重试机制）
const deleteConversation = async (id, retryCount = 0) => {
  try {
    console.log('删除会话:', id);
    await mcpApi.delete('/conversation/delete', id, {
      onSuccess: () => {
        console.log('删除会话成功:', id);
        const index = conversations.value.findIndex((c) => c.id === id);
        if (index !== -1) {
          conversations.value.splice(index, 1);
        }

        if (id === conversationId.value) {
          console.log('当前会话被删除，生成新会话ID');
          generateNewConversationId();
        }

        localStorage.setItem(STORAGE_KEY, JSON.stringify(conversations.value));
        console.log('localStorage已更新');
      },
      onError: (errorMessage) => {
        console.error('删除会话失败:', errorMessage);

        const lastMessage = messages.value[messages.value.length - 1];
        if (!lastMessage || !lastMessage.isError) {
          messages.value = [
            ...messages.value,
            {
              sender: 'system',
              content: `删除会话失败${retryCount > 0 ? ` (重试 ${retryCount}/3)` : ''}`,
              isError: true,
              timestamp: new Date().toISOString()
            }
          ];
        }

        if (retryCount < 3) {
          setTimeout(() => deleteConversation(id, retryCount + 1), 1000 * (retryCount + 1));
        }
      }
    });
  } catch (error) {
    console.error('删除会话失败:', error);

    const lastMessage = messages.value[messages.value.length - 1];
    if (!lastMessage || !lastMessage.isError) {
      messages.value = [
        ...messages.value,
        {
          sender: 'system',
          content: `删除会话失败${retryCount > 0 ? ` (重试 ${retryCount}/3)` : ''}`,
          isError: true,
          timestamp: new Date().toISOString()
        }
      ];
    }

    if (retryCount < 3) {
      setTimeout(() => deleteConversation(id, retryCount + 1), 1000 * (retryCount + 1));
    }
  }
};

// 监听全局store中的右侧边栏状态变化
watch(
  () => globalStore.showRightSidebar,
  (newValue) => {
    showSidebar.value = newValue;
  }
);

// 生命周期钩子
onMounted(initializeApp);
</script>

<style scoped>
.app-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
}

.chat-container {
  display: flex;
  flex: 1;
  overflow: hidden;
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  background: var(--color-background, #ffffff);
}

.chat-sidebar {
  background-color: #ffffff;
  width: 320px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  position: fixed;
  top: 0;
  right: -320px;
  height: 100vh;
  z-index: 1000;
  box-shadow: -2px 0 20px rgba(0, 0, 0, 0.1);
  border-left: 1px solid var(--color-border);
}

.chat-sidebar.show {
  right: 0;
}

/* 遮罩层 */
.chat-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.4);
  z-index: 999;
  opacity: 0;
  visibility: hidden;
  transition: all 0.3s ease;
  backdrop-filter: blur(2px);
}

.chat-overlay.show {
  opacity: 1;
  visibility: visible;
}

.chat-sidebar-header {
  padding: 24px;
  border-bottom: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--color-background);
  position: sticky;
  top: 0;
  z-index: 10;
}

.chat-sidebar-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text, #333333);
}

.chat-conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  scrollbar-width: thin;
  scrollbar-color: var(--color-border) transparent;
}

.chat-conversation-list::-webkit-scrollbar {
  width: 4px;
}

.chat-conversation-list::-webkit-scrollbar-track {
  background: transparent;
}

.chat-conversation-list::-webkit-scrollbar-thumb {
  background: var(--color-border);
  border-radius: 2px;
}

.chat-conversation-item {
  padding: 16px;
  border-radius: 12px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;
  border: 1px solid transparent;
  background: var(--color-background);
}

.chat-conversation-item:hover {
  background: var(--color-hover);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.chat-conversation-item.active {
  background: var(--color-primary-light);
  border-color: var(--color-primary);
  box-shadow: 0 2px 12px rgba(var(--color-primary-rgb), 0.15);
}

.chat-conversation-content {
  flex: 1;
  min-width: 0;
  margin-left: 48px;
}

.chat-conversation-title {
  font-weight: 500;
  margin-bottom: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  color: var(--color-text, #333333);
  line-height: 1.4;
}

.chat-conversation-date {
  font-size: 12px;
  color: var(--color-text-secondary, #666666);
  line-height: 1.3;
}

.chat-delete-btn {
  padding: 6px 12px;
  font-size: 12px;
  opacity: 0;
  visibility: hidden;
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 1;
  transition: all 0.2s ease;
  border-radius: 6px;
}

.chat-conversation-item:hover .chat-delete-btn {
  opacity: 1;
  visibility: visible;
}

.chat-main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  grid-template-rows: 1fr auto;
  background: var(--color-background);
  overflow: hidden;
  /* 防止整个容器滚动 */
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 24px 0 24px;
  min-height: 0;
  scroll-behavior: smooth;
  scrollbar-width: thin;
  scrollbar-color: var(--color-border) transparent;
  height: 100%;
  /* 确保消息区域占据全部可用高度 */
}

.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: transparent;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: var(--color-border);
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb:hover {
  background: var(--color-text-secondary);
}

.chat-message {
  margin-bottom: 20px;
  padding: 16px 20px;
  border-radius: 20px;
  max-width: fit-content;
  min-width: auto;
  width: auto;
  word-break: break-word;
  line-height: 1.6;
  animation: messageSlideIn 0.3s ease-out;
  position: relative;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

@keyframes messageSlideIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.chat-user-message {
  background: #eff6ff;
  color: #1e40af;
  margin-left: auto;
  margin-right: 0;
  border-bottom-right-radius: 8px;
  text-align: right;
  box-shadow: 0 4px 12px rgba(var(--color-primary-rgb, 59, 130, 246), 0.15);
}

.chat-bot-message {
  background: var(--color-card, #f8fafc);
  color: var(--color-text, #374151);
  margin-right: auto;
  margin-left: 0;
  border-bottom-left-radius: 8px;
  text-align: left;
  border: 1px solid var(--color-border, #e5e7eb);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
  max-width: fit-content;
  min-width: auto;
  width: auto;
}

.chat-input {
  display: flex;
  padding: 24px 24px;
  border-top: 1px solid var(--color-border);
  background: var(--color-card);
  gap: 16px;
  align-items: flex-end;
  backdrop-filter: blur(10px);
  box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.1);
  min-height: 80px;
  /* 确保最小高度 */
}

.chat-input .var-input {
  flex: 1;
  width: 100%;
  min-height: 48px;
  /* 确保输入框有最小高度 */
}

.chat-input .var-input textarea {
  min-height: 48px !important;
  /* 确保textarea有最小高度 */
  resize: vertical;
  /* 允许垂直调整大小 */
}

.chat-input .var-button {
  flex-shrink: 0;
  margin-bottom: 8px;
  /* 与输入框底部对齐 */
}

.send-button {
  min-width: 80px;
}

.send-icon {
  display: block;
  font-size: 18px;
}

.chat-empty-state {
  text-align: center;
  padding: 80px 20px;
  color: var(--color-text-secondary);
  font-size: 20px;
  font-weight: 500;
  animation: fadeInUp 0.6s ease-out;
}

.chat-empty-hint {
  margin-top: 12px;
  font-size: 16px;
  color: var(--color-text-tertiary);
  font-weight: 400;
  line-height: 1.5;
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 加载状态 */
.chat-loading {
  display: inline-block;
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-radius: 50%;
  border-top-color: white;
  animation: spin 1s ease-in-out infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 消息时间戳 */
.chat-message-time {
  font-size: 11px;
  color: var(--color-text-tertiary);
  margin-top: 6px;
  opacity: 0.7;
  text-align: right;
}

.chat-bot-message .chat-message-time {
  text-align: left;
}

/* 响应式设计 */
@media (max-width: 1024px) {
  .chat-sidebar {
    width: 380px;
  }

  .chat-input {
    padding: 20px;
    gap: 14px;
  }
}

@media (max-width: 768px) {
  .chat-container {
    border-radius: 0;
    max-width: 100%;
  }

  .chat-sidebar {
    width: 340px;
  }

  .chat-messages {
    padding: 20px 20px 100px 20px;
    /* 底部增加 padding 防止被输入框遮挡 */
    height: 100%;
    /* 确保在移动端也占据全部高度 */
  }

  .chat-message {
    padding: 14px 18px;
    font-size: 15px;
    border-radius: 18px;
  }

  .chat-input {
    padding: 16px;
    gap: 12px;
    min-height: 70px;
    /* 移动端最小高度调整 */
  }

  .chat-input .var-input {
    min-height: 42px;
    /* 移动端输入框最小高度 */
  }

  .chat-input .var-input textarea {
    min-height: 42px !important;
    /* 移动端textarea最小高度 */
  }

  .chat-input .var-button {
    min-width: 48px;
    height: 48px;
    border-radius: 50%;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    visibility: visible !important;
    opacity: 1 !important;
  }

  .send-icon {
    display: inline;
    font-size: 20px;
    margin: 0;
  }

  .chat-empty-state {
    padding: 60px 20px;
    font-size: 18px;
  }

  .chat-empty-hint {
    font-size: 14px;
  }
}

@media (max-width: 640px) {
  .chat-sidebar {
    width: 300px;
  }

  .chat-sidebar-header {
    padding: 20px;
  }

  .chat-sidebar-header h3 {
    font-size: 16px;
  }

  .chat-conversation-item {
    padding: 14px;
  }

  .chat-messages {
    padding: 16px 16px 90px 16px;
    /* 底部增加 padding 防止被输入框遮挡 */
    height: 100%;
    /* 确保在移动端也占据全部高度 */
  }

  .chat-message {
    padding: 12px 16px;
    font-size: 14px;
    border-radius: 16px;
  }

  .chat-input {
    padding: 14px;
    gap: 10px;
    min-height: 65px;
    /* 小屏幕最小高度调整 */
  }

  .chat-input .var-input {
    min-height: 40px;
    /* 小屏幕输入框最小高度 */
  }

  .chat-input .var-input textarea {
    min-height: 40px !important;
    /* 小屏幕textarea最小高度 */
  }

  .chat-input .var-button {
    min-width: 44px;
    height: 44px;
    border-radius: 50%;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .send-icon {
    display: inline;
    font-size: 18px;
  }
}

@media (max-width: 480px) {
  .chat-sidebar {
    width: 280px;
  }

  .chat-sidebar-header {
    padding: 16px;
    flex-direction: column;
    gap: 8px;
  }

  .chat-sidebar-header h3 {
    font-size: 14px;
  }

  .chat-conversation-title {
    font-size: 13px;
  }

  .chat-conversation-date {
    font-size: 11px;
  }

  .chat-message {
    padding: 10px 14px;
    font-size: 13px;
    border-radius: 14px;
  }

  .chat-input {
    padding: 12px;
    gap: 8px;
    min-height: 60px;
    /* 超小屏幕最小高度调整 */
  }

  .chat-input .var-input {
    min-height: 38px;
    /* 超小屏幕输入框最小高度 */
  }

  .chat-input .var-input textarea {
    min-height: 38px !important;
    /* 超小屏幕textarea最小高度 */
  }

  .chat-input .var-button {
    min-width: 40px;
    height: 40px;
    border-radius: 50%;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .send-icon {
    display: inline;
    font-size: 16px;
  }

  .chat-empty-state {
    padding: 40px 16px;
    font-size: 16px;
  }

  .chat-empty-hint {
    font-size: 13px;
  }

  /* 移动模式下删除按钮默认显示 */
  .chat-delete-btn {
    opacity: 1;
    visibility: visible;
  }
}

/* 滚动条美化 */
.chat-messages::-webkit-scrollbar,
.chat-conversation-list::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track,
.chat-conversation-list::-webkit-scrollbar-track {
  background: transparent;
}

.chat-messages::-webkit-scrollbar-thumb,
.chat-conversation-list::-webkit-scrollbar-thumb {
  background: var(--color-border);
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb:hover,
.chat-conversation-list::-webkit-scrollbar-thumb:hover {
  background: var(--color-text-secondary);
}

/* 消息内容样式优化 */
.chat-message :deep(p) {
  margin: 0.5em 0;
}

.chat-message :deep(a) {
  color: var(--color-primary);
  text-decoration: underline;
}

.chat-message :deep(code) {
  background: rgba(0, 0, 0, 0.1);
  padding: 0.2em 0.4em;
  border-radius: 3px;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.9em;
}

.chat-message :deep(pre) {
  background: rgba(0, 0, 0, 0.05);
  padding: 1em;
  border-radius: 8px;
  overflow-x: auto;
  margin: 1em 0;
}

.chat-message :deep(blockquote) {
  border-left: 4px solid var(--color-border);
  margin: 1em 0;
  padding-left: 1em;
  color: var(--color-text-secondary);
}

.chat-message :deep(ul),
.chat-message :deep(ol) {
  margin: 0.5em 0;
  padding-left: 1.5em;
}

.chat-message :deep(li) {
  margin: 0.25em 0;
}

/* 高亮当前正在输入的消息 */
.chat-message.streaming {
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0% {
    opacity: 1;
  }

  50% {
    opacity: 0.8;
  }

  100% {
    opacity: 1;
  }
}

/* 错误消息样式 */
.chat-message.error {
  background: var(--color-danger-light);
  border-color: var(--color-danger);
  color: var(--color-danger);
}

/* 信息消息样式 */
.chat-message.info {
  background: var(--color-info-light);
  border-color: var(--color-info);
  color: var(--color-info);
}
</style>
