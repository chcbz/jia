<template>
  <div style="height: 100%">
    <var-action-sheet
      :actions="actionSheetActions"
      v-model:show="showActionSheet"
      @select="handleActionSelect"
      @update:show="onActionSheetShowChange"
    />
    <div class="calendar-container" v-if="showCalendar">
      <div class="calendar-header">
        <var-button text @click="prevMonth">
          <var-icon name="chevron-left" />
        </var-button>
        <h2>{{ currentMonth }}</h2>
        <var-button text @click="nextMonth">
          <var-icon name="chevron-right" />
        </var-button>
      </div>

      <div class="calendar-grid">
        <div class="calendar-weekdays">
          <div v-for="day in weekdays" :key="day" class="weekday">
            {{ day }}
          </div>
        </div>

        <div class="calendar-days">
          <div
            v-for="day in calendarDays"
            :key="day.date"
            :class="[
              'day',
              {
                today: day.isToday,
                'current-month': day.isCurrentMonth,
                'has-tasks': day.taskCount > 0,
                'selected': selectedDate === day.date && day.isCurrentMonth
              }
            ]"
            @click="selectCalendarDay(day)"
          >
            <div class="day-number">{{ day.day }}</div>
            <div v-if="day.taskCount > 0" class="task-indicator">
              <div v-if="day.taskCount > 0" class="task-type-dots">
                <span 
                  v-if="day.payCount > 0" 
                  class="type-dot type-pay"
                  :style="{ opacity: Math.min(day.payCount / 3, 1) }"
                ></span>
                <span 
                  v-if="day.notifyCount > 0" 
                  class="type-dot type-notify"
                  :style="{ opacity: Math.min(day.notifyCount / 3, 1) }"
                ></span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 任务列表区域 -->
    <div class="tasks-section" v-if="listPlan.length > 0">
      <div class="tasks-header">
        <h3>{{ formatSelectedDate }}</h3>
        <span class="tasks-count">{{ listPlan.length }} 个任务</span>
      </div>
      <!-- 添加滚动容器 -->
      <div class="list-scroll-container">
        <var-list class="tasks-list">
          <var-cell
            v-for="item in listPlan"
            :key="item.id"
            ripple
            @click="doShowDetail(item)"
          >
            <template #title>
              <div class="task-title">
                <span class="task-type-badge" :class="getTaskTypeClass(item.type)">
                  {{ typeDict(item.type) }}
                </span>
                <span class="task-name">{{ item.name }}</span>
              </div>
            </template>
            <template #description>
              <div class="task-description">
                <span v-if="item.description" class="task-desc-text">{{ item.description }}</span>
                <span class="task-time">
                  {{ formatTaskTime(item) }}
                </span>
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
      <var-empty description="暂无任务" />
    </div>

    <!-- 任务详情弹窗 -->
    <var-dialog v-model="taskDetailShow" :title="currentTask?.name">
      <div class="task-detail-content" v-if="currentTask">
        <div class="detail-section">
          <h4>任务信息</h4>
          <div class="detail-item">
            <span class="detail-label">任务类型:</span>
            <span class="detail-value">{{ typeDict(currentTask.type) }}</span>
          </div>
          <div class="detail-item" v-if="currentTask.description">
            <span class="detail-label">描述:</span>
            <span class="detail-value">{{ currentTask.description }}</span>
          </div>
          <div class="detail-item" v-if="currentTask.amount > 0">
            <span class="detail-label">金额:</span>
            <span class="detail-value amount">￥{{ formatAmount(currentTask.amount) }}</span>
          </div>
        </div>
        
        <div class="detail-section">
          <h4>时间信息</h4>
          <div class="detail-item">
            <span class="detail-label">执行时间:</span>
            <span class="detail-value">
              {{ formatTaskTime(currentTask, true) }}
            </span>
          </div>
          <div class="detail-item" v-if="taskDetailData.periodText">
            <span class="detail-label">重复周期:</span>
            <span class="detail-value">{{ taskDetailData.periodText }}</span>
          </div>
        </div>
        
        <div class="detail-actions">
          <var-button type="primary" block @click="taskDetailShow = false">
            关闭
          </var-button>
        </div>
      </div>
    </var-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import { useGlobalStore } from '../stores/global'
import { taskApi } from '../composables/useHttp'

const router = useRouter()
const { t } = useI18n()
const globalStore = useGlobalStore()

// 响应式数据
const showCalendar = ref(true)
const currentDate = ref(dayjs())
const selectedDate = ref(dayjs().format('YYYY-MM-DD'))
const monthTasks = ref([])
const listPlan = ref([])
const currentTask = ref(null)
const taskDetailShow = ref(false)
const taskDetailData = ref({})
const showActionSheet = ref(false)
const actionSheetActions = ref([
  { name: t('task.add'), key: 'add' },
  { name: t('app.task_list'), key: 'list' },
  { name: t('app.task_history'), key: 'history' }
])

// 常量
const weekdays = ['日', '一', '二', '三', '四', '五', '六']
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

// 计算属性
const currentMonth = computed(() => {
  return currentDate.value.format('YYYY年MM月')
})

const formatSelectedDate = computed(() => {
  const date = dayjs(selectedDate.value)
  const today = dayjs()
  if (date.isSame(today, 'day')) {
    return `今天 (${date.format('MM月DD日')})`
  }
  return date.format('MM月DD日 dddd')
})

const calendarDays = computed(() => {
  const startOfMonth = currentDate.value.startOf('month')
  const endOfMonth = currentDate.value.endOf('month')
  const startDay = startOfMonth.day()
  const daysInMonth = endOfMonth.date()
  
  const daysArray = []
  const today = dayjs().format('YYYY-MM-DD')
  
  // 上个月的最后几天
  const prevMonthDays = startDay
  for (let i = prevMonthDays - 1; i >= 0; i--) {
    const date = startOfMonth.subtract(i + 1, 'day')
    const dateStr = date.format('YYYY-MM-DD')
    const dayTasks = getTasksForDate(dateStr)
    daysArray.push(createDayObject(date, dateStr, today, dayTasks, false))
  }
  
  // 当前月的天数
  for (let i = 1; i <= daysInMonth; i++) {
    const date = startOfMonth.date(i)
    const dateStr = date.format('YYYY-MM-DD')
    const dayTasks = getTasksForDate(dateStr)
    daysArray.push(createDayObject(date, dateStr, today, dayTasks, true))
  }
  
  // 下个月的前几天
  const remainingCells = 42 - daysArray.length
  for (let i = 1; i <= remainingCells; i++) {
    const date = endOfMonth.add(i, 'day')
    const dateStr = date.format('YYYY-MM-DD')
    const dayTasks = getTasksForDate(dateStr)
    daysArray.push(createDayObject(date, dateStr, today, dayTasks, false))
  }
  
  return daysArray
})

// 方法
const createDayObject = (date, dateStr, today, dayTasks, isCurrentMonth) => {
  const payCount = dayTasks.filter(task => task.type > 1).length
  const notifyCount = dayTasks.filter(task => task.type <= 1).length
  
  return {
    date: dateStr,
    day: date.date(),
    isCurrentMonth,
    isToday: dateStr === today,
    taskCount: dayTasks.length,
    payCount,
    notifyCount
  }
}

const getTasksForDate = (dateStr) => {
  return monthTasks.value.filter(task => {
    try {
      return dayjs(task.executeTime).format('YYYY-MM-DD') === dateStr
    } catch (error) {
      console.warn('日期解析错误:', task.executeTime, error)
      return false
    }
  })
}

const prevMonth = () => {
  currentDate.value = currentDate.value.subtract(1, 'month')
  fetchTasks()
}

const nextMonth = () => {
  currentDate.value = currentDate.value.add(1, 'month')
  fetchTasks()
}

const selectCalendarDay = (day) => {
  if (!day.isCurrentMonth) {
    // 点击非当前月日期，切换到该月
    currentDate.value = dayjs(day.date)
    selectedDate.value = day.date
    fetchTasks()
    return
  }
  
  selectedDate.value = day.date
  const dayTasks = getTasksForDate(day.date)
  listPlan.value = dayTasks
}

const fetchTasks = async () => {
  try {
    const firstDay = currentDate.value.startOf('month')
    const lastDay = currentDate.value.endOf('month')
    const jiacn = globalStore.getJiacn

    taskApi.search('/item/search', {
      search: {
        jiacn: jiacn,
        timeStart: firstDay.valueOf(),
        timeEnd: lastDay.valueOf()
      }
    }, {
      onSuccess: (data) => {
        monthTasks.value = Array.isArray(data.data) ? data.data : []
        
        // 更新选中日期的任务列表
        const dayTasks = getTasksForDate(selectedDate.value)
        listPlan.value = dayTasks
      },
      onError: (error) => {
        console.error('获取任务失败:', error)
        monthTasks.value = []
        listPlan.value = []
      }
    })
  } catch (error) {
    console.error('任务请求异常:', error)
  }
}

const doShowDetail = async (item) => {
  currentTask.value = item
  
  try {
    const data = await taskApi.getById('/get', item.planId)
    if (data?.data) {
      taskDetailData.value = {
        periodText: item.crond || periodMap[item.period] || '一次性任务',
        startTime: data.data.startTime,
        endTime: data.data.endTime
      }
    }
  } catch (error) {
    console.warn('获取任务详情失败:', error)
    taskDetailData.value = {
      periodText: item.crond || periodMap[item.period] || '一次性任务'
    }
  }
  
  taskDetailShow.value = true
}

const typeDict = (type) => {
  return type > 1 ? t('task.type_pay') : t('task.type_notify')
}

const getTaskTypeClass = (type) => {
  return type > 1 ? 'type-pay' : 'type-notify'
}

const formatTaskTime = (task, full = false) => {
  try {
    if (task.type > 1) {
      // 支付任务显示执行时间
      return dayjs(task.executeTime).format(full ? 'YYYY-MM-DD HH:mm' : 'HH:mm')
    } else {
      // 通知任务显示时间段
      const start = dayjs(taskDetailData.value.startTime || task.executeTime)
      const end = dayjs(taskDetailData.value.endTime || task.executeTime)
      
      if (full) {
        return `${start.format('YYYY-MM-DD HH:mm')} ~ ${end.format('YYYY-MM-DD HH:mm')}`
      }
      return `${start.format('HH:mm')}~${end.format('HH:mm')}`
    }
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

// ActionSheet 处理
const handleActionSelect = (action) => {
  switch (action.key) {
    case 'add':
      router.push({ name: 'TaskAdd' })
      break
    case 'list':
      router.push({ name: 'TaskList' })
      break
    case 'history':
      router.push({ name: 'TaskHistory' })
      break
  }
}

const onActionSheetShowChange = (show) => {
  // 当 action sheet 隐藏且右侧边栏当前显示时，触发 toggleRightSidebar
  if (!show && globalStore.showRightSidebar) {
    globalStore.toggleRightSidebar()
  }
}

// 监听右侧边栏显示状态
watch(
  () => globalStore.showRightSidebar,
  (newValue) => {
    showActionSheet.value = newValue
  }
)

// 生命周期
onMounted(() => {
  globalStore.setTitle(t('app.title'))
  globalStore.setShowBack(false)
  globalStore.setShowMore(true)
  
  fetchTasks()
})
</script>

<style scoped>
.calendar-container {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 16px;
  padding: 20px;
  margin: 16px;
  margin-bottom: 24px;
  color: white;
  box-shadow: 0 8px 32px rgba(102, 126, 234, 0.2);
}

.calendar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.calendar-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: white;
}

.calendar-header .var-button {
  color: white;
}

.calendar-grid {
  display: flex;
  flex-direction: column;
}

.calendar-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  text-align: center;
  margin-bottom: 12px;
  font-weight: 500;
  opacity: 0.9;
}

.calendar-days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 8px;
}

.day {
  aspect-ratio: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  cursor: pointer;
  position: relative;
  transition: all 0.3s ease;
  padding: 4px;
}

.day:hover {
  background: rgba(255, 255, 255, 0.1);
  transform: translateY(-2px);
}

.day.current-month {
  background: rgba(255, 255, 255, 0.05);
}

.day.today {
  background: rgba(255, 255, 255, 0.2);
  font-weight: bold;
}

.day.selected {
  background: rgba(255, 255, 255, 0.3);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

.day-number {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 2px;
}

.day:not(.current-month) .day-number {
  opacity: 0.5;
}

.task-indicator {
  position: absolute;
  top: 2px;
  right: 2px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
}

.task-type-dots {
  display: flex;
  gap: 2px;
  justify-content: center;
}

.type-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.type-dot.type-pay {
  background: #ff6b6b;
}

.type-dot.type-notify {
  background: #4dabf7;
}

/* 任务列表样式 */
.tasks-section {
  margin: 0 16px;
  background: white;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
  /* 动态高度：根据屏幕高度自适应 */
  height: calc(100vh - 320px);
  min-height: 300px;
  max-height: 500px;
}

.tasks-header {
  padding: 20px 20px 12px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0; /* 防止头部被压缩 */
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

/* 滚动容器 */
.list-scroll-container {
  flex: 1;
  overflow: hidden;
  position: relative;
}

.tasks-list {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

/* 任务单元格样式 */
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

.task-type-badge.type-pay {
  background: #fff5f5;
  color: #ff6b6b;
}

.task-type-badge.type-notify {
  background: #f0f9ff;
  color: #4dabf7;
}

.task-name {
  flex: 1;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-description {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 4px;
}

.task-desc-text {
  font-size: 13px;
  color: #666;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.task-time {
  font-size: 12px;
  color: #999;
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

/* 任务详情样式 */
.task-detail-content {
  padding: 0 4px;
}

.detail-section {
  margin-bottom: 20px;
}

.detail-section h4 {
  margin: 0 0 12px 0;
  font-size: 15px;
  font-weight: 600;
  color: #333;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.detail-item {
  display: flex;
  margin-bottom: 10px;
  font-size: 14px;
}

.detail-label {
  width: 80px;
  color: #666;
  flex-shrink: 0;
}

.detail-value {
  flex: 1;
  color: #333;
  word-break: break-word;
}

.detail-value.amount {
  font-weight: 600;
  color: #ff6b6b;
}

.detail-actions {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
}

/* 滚动条样式 */
.tasks-list::-webkit-scrollbar {
  width: 6px;
}

.tasks-list::-webkit-scrollbar-track {
  background: #f5f5f5;
  border-radius: 3px;
}

.tasks-list::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
  transition: background 0.3s;
}

.tasks-list::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

/* Firefox 滚动条样式 */
.tasks-list {
  scrollbar-width: thin;
  scrollbar-color: #c1c1c1 #f5f5f5;
}

/* VarList 单元格样式调整 */
.tasks-list :deep(.var-cell) {
  padding: 12px 16px;
  min-height: 60px;
}

.tasks-list :deep(.var-cell__title) {
  flex: 1;
  min-width: 0;
}

/* 响应式调整 */
@media (max-width: 375px) {
  .calendar-container {
    margin: 12px;
    padding: 16px;
  }
  
  .calendar-days {
    gap: 6px;
  }
  
  .day-number {
    font-size: 13px;
  }
  
  .tasks-section {
    height: calc(100vh - 280px);
    min-height: 250px;
  }
  
  .tasks-header {
    padding: 16px 16px 12px;
  }
}

@media (min-width: 376px) and (max-width: 768px) {
  .tasks-section {
    height: calc(100vh - 300px);
  }
}

@media (min-width: 769px) {
  .tasks-section {
    height: calc(100vh - 350px);
    max-height: 600px;
  }
}
</style>