<template>
  <div class="task-history-container">
    <var-action-sheet 
      :actions="opMenu" 
      v-model:show="showOpMenu" 
      @select="onClickOpMenu"
    />
    
    <div class="tasks-section" v-if="list.length > 0">
      <div class="tasks-header">
        <h3>{{ t('app.task_history') }}</h3>
        <span class="tasks-count">{{ list.length }} 个历史任务</span>
      </div>
      
      <div class="tasks-list-container">
        <var-list>
          <var-cell
            v-for="item in list"
            :key="item.id"
            ripple
            @click="doShowOpMenu(item)"
          >
            <template #default>
              <div class="task-title">
                <span class="task-type-badge" :class="getTaskTypeClass(item.type)">
                  {{ typeDict(item.type) }}
                </span>
                <span class="task-name">{{ item.name }}</span>
              </div>
            </template>
            <template #description>
              <div class="task-description">
                <span class="task-time">
                  {{ formatTaskTime(item) }}
                </span>
                <span v-if="item.description" class="task-desc-text">{{ item.description }}</span>
              </div>
            </template>
            <template #extra>
              <div class="task-extra">
                <span v-if="item.amount > 0" class="task-amount">
                  ￥{{ formatAmount(item.amount) }}
                </span>
                <var-icon name="chevron-right" size="16" />
              </div>
            </template>
          </var-cell>
        </var-list>
      </div>
    </div>

    <div v-else class="empty-tasks">
      <var-empty description="暂无历史任务" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useGlobalStore } from '../stores/global'
import { useApiStore } from '../stores/api'
import { useUtilStore } from '../stores/util'
import dayjs from 'dayjs'
import { Dialog } from '@varlet/ui'
import { taskApi } from '../composables/useHttp'

// 路由器
const router = useRouter()
const { t } = useI18n()

// Pinia stores
const globalStore = useGlobalStore()
const apiStore = useApiStore()
const utilStore = useUtilStore()

// 响应式数据
const list = ref([])
const opMenu = ref([
  { name: t('app.del'), key: 'del' }
])
const showOpMenu = ref(false)
const selectId = ref(0)
const currentTask = ref(null)

// 常量
const periodMap = {
  0: '长期',
  1: '每年',
  2: '每月',
  3: '每周',
  5: '每日',
  11: '每小时',
  12: '每分钟',
  13: '每秒',
  6: '指定日期'
}

// 方法
const doShowOpMenu = (item) => {
  selectId.value = item.id
  currentTask.value = item
  showOpMenu.value = true
}

const typeDict = (type) => {
  const typeMap = {
    1: t('task.type_notify'),
    2: t('task.type_target'),
    3: t('task.type_repayment'),
    4: t('task.type_fixed_income')
  }
  return typeMap[type] || t('task.type_notify')
}

const getTaskTypeClass = (type) => {
  const classMap = {
    1: 'type-notify',
    2: 'type-target',
    3: 'type-repayment',
    4: 'type-income'
  }
  return classMap[type] || 'type-notify'
}

const formatTaskTime = (task) => {
  try {
    const start = dayjs(utilStore.fromTimeStamp(task.startTime))
    const end = dayjs(utilStore.fromTimeStamp(task.endTime))
    
    // 通知任务显示时间段
    return `${start.format('YYYY-MM-DD HH:mm')} ~ ${end.format('YYYY-MM-DD HH:mm')}`
  } catch (error) {
    return '时间未知'
  }
}

const formatAmount = (amount) => {
  return Number(amount).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}

const onClickOpMenu = (key) => {
  if (key === 'del') {
    Dialog({
      title: t('task.del_alert'),
      message: `确定要删除历史任务 "${currentTask.value?.name}" 吗？`,
      confirmButton: true,
      cancelButton: true,
      confirmButtonText: t('app.confirm'),
      cancelButtonText: t('app.cancel'),
      onConfirm: () => {
        taskApi.delete('/delete', selectId.value, {
          onSuccess: (data) => {
            if (data.code === 'E0') {
              Dialog({
                title: t('app.notify'),
                message: data.data.msg,
                confirmButtonText: t('app.confirm'),
                onConfirm: () => {
                  // 重新加载数据
                  fetchTasks()
                }
              })
            } else {
              Dialog({
                title: t('app.alert'),
                message: data.data.msg,
                confirmButtonText: t('app.confirm')
              })
            }
          },
          onError: (error) => {
            Dialog({
              title: t('app.error'),
              message: t('app.network_error'),
              confirmButtonText: t('app.confirm')
            })
            console.error('删除历史任务失败:', error)
          }
        })
      }
    })
  }
  showOpMenu.value = false
}

const fetchTasks = () => {
  const jiacn = globalStore.getJiacn
  
  taskApi.search('/search', {
    search: {
      jiacn: jiacn,
      historyFlag: 1
    }
  }, {
    onSuccess: (data) => {
      list.value = Array.isArray(data.data) ? data.data : []
    },
    onError: (error) => {
      console.error('获取历史任务失败:', error)
      list.value = []
    }
  })
}

// 生命周期
onMounted(() => {
  globalStore.setTitle(t('app.task_history'))
  globalStore.setShowBack(true)
  globalStore.setShowMore(false)
  
  fetchTasks()
})
</script>

<style scoped>
.task-history-container {
  min-height: 100vh;
  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
  padding: 16px;
}

.tasks-section {
  background: white;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 100px);
}

.tasks-header {
  padding: 20px 20px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.tasks-header h3 {
  margin: 0 0 8px 0;
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.tasks-count {
  font-size: 12px;
  color: #999;
}

.tasks-list-container {
  flex: 1;
  overflow-y: auto;
  max-height: calc(100vh - 180px);
}

.task-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-type-badge {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 500;
  flex-shrink: 0;
}

.task-type-badge.type-notify {
  background: #f0f9ff;
  color: #4dabf7;
}

.task-type-badge.type-target {
  background: #fff7e6;
  color: #fa8c16;
}

.task-type-badge.type-repayment {
  background: #fff2f0;
  color: #f5222d;
}

.task-type-badge.type-income {
  background: #f6ffed;
  color: #52c41a;
}

.task-name {
  flex: 1;
  font-weight: 500;
}

.task-description {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.task-desc-text {
  font-size: 13px;
  color: #666;
  line-height: 1.4;
}

.task-time {
  font-size: 12px;
  color: #999;
  flex-shrink: 0;
}

.task-extra {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-amount {
  font-weight: 600;
  color: #ff6b6b;
  font-size: 14px;
}

.empty-tasks {
  margin: 32px 16px;
  text-align: center;
}

/* 响应式调整 */
@media (max-width: 375px) {
  .task-history-container {
    padding: 12px;
  }
  
  .tasks-header {
    padding: 16px 16px 10px;
  }
  
  .tasks-header h3 {
    font-size: 15px;
  }
}
</style>
