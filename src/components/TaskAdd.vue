<template>
  <div class="task-add-container">
    <div class="form-card">
      <div class="form-content">
        <div class="form-section">
          <h3 class="section-title">{{ t('task.basic_info') }}</h3>
          <var-select
            :placeholder="t('task.type')"
            :options="typeOptions"
            v-model="type"
            @change="changeType"
            class="form-field"
            label-key="text"
            value-key="value"
          />
          <var-select
            :placeholder="t('task.period')"
            :options="periodOptions"
            v-model="period"
            @change="changePeriod"
            class="form-field"
            label-key="text"
            value-key="value"
          />
          <var-input 
            :label="t('task.name')" 
            v-model="name" 
            class="form-field"
            :placeholder="t('task.name_placeholder')"
          />
          <var-input 
            :label="t('task.description')" 
            v-model="description" 
            textarea 
            :rows="3" 
            class="form-field"
            :placeholder="t('task.description_placeholder')"
          />
        </div>

        <div class="form-section" v-show="startTimeShow || endTimeShow">
          <h3 class="section-title">{{ t('task.time_info') }}</h3>
          <var-date-picker
            v-model="start_time"
            :max-date="new Date(2100, 0, 1)"
            format="YYYY-MM-DD HH:mm"
            :minute-list="['00', '15', '30', '45']"
            :title="t('task.start_time')"
            v-show="startTimeShow"
            class="form-field"
          />
          <var-date-picker
            v-model="end_time"
            :max-date="new Date(2100, 0, 1)"
            format="YYYY-MM-DD HH:mm"
            :minute-list="['00', '15', '30', '45']"
            :title="t('task.end_time')"
            v-show="endTimeShow"
            class="form-field"
          />
        </div>

        <div class="form-section">
          <h3 class="section-title">{{ t('task.other_info') }}</h3>
          <var-switch 
            :label="t('task.lunar')" 
            v-model="lunar" 
            :active-value="1" 
            :inactive-value="0" 
            class="form-field"
          />
          <var-input
            :label="t('task.amount')"
            v-model="amount"
            type="tel"
            :maxlength="6"
            v-show="amountShow"
            class="form-field"
            :placeholder="t('task.amount_placeholder')"
          />
          <var-switch
            :label="t('task.remind')"
            v-model="remind"
            :active-value="1"
            :inactive-value="0"
            class="form-field"
          />
        </div>
      </div>

      <div class="form-actions">
        <var-button 
          type="primary" 
          block 
          @click="doAdd" 
          :loading="loading"
          class="submit-button"
        >
          {{ t('app.save') }}
        </var-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Dialog } from '@varlet/ui'
import { useGlobalStore } from '../stores/global'
import { useApiStore } from '../stores/api'
import { useUtilStore } from '../stores/util'
import { taskApi } from '../composables/useHttp'

// 路由器
const router = useRouter()
const { t } = useI18n()

// Pinia stores
const globalStore = useGlobalStore()
const apiStore = useApiStore()
const utilStore = useUtilStore()

// 响应式数据
const type = ref('')
const period = ref('')
const name = ref('')
const description = ref('')
const start_time = ref('')
const startTimeShow = ref(false)
const end_time = ref('')
const endTimeShow = ref(false)
const amount = ref(null)
const amountShow = ref(false)
const remind = ref(1)  // 改为数字类型以匹配Switch组件的active-value
const lunar = ref(0)   // 改为数字类型以匹配Switch组件的inactive-value
const loading = ref(false)

// 计算属性
const typeOptions = ref([
  {
    text: t('task.type_notify'),
    value: '1'
  },
  {
    text: t('task.type_target'),
    value: '2'
  },
  {
    text: t('task.type_repayment'),
    value: '3'
  },
  {
    text: t('task.type_fixed_income'),
    value: '4'
  }
])

const periodOptions = ref([
  {
    text: t('task.period_long_term'),
    value: '0'
  },
  {
    text: t('task.period_yearly'),
    value: '1'
  },
  {
    text: t('task.period_monthly'),
    value: '2'
  },
  {
    text: t('task.period_weekly'),
    value: '3'
  },
  {
    text: t('task.period_daily'),
    value: '5'
  },
  {
    text: t('task.period_hourly'),
    value: '11'
  },
  {
    text: t('task.period_minutely'),
    value: '12'
  },
  {
    text: t('task.period_secondly'),
    value: '13'
  },
  {
    text: t('task.period_specific_date'),
    value: '6'
  }
])

// 方法
const doAdd = () => {
  // 表单验证
  if (!name.value.trim()) {
    Dialog({
      title: t('app.alert'),
      message: t('task.name_required'),
      confirmButtonText: t('app.confirm')
    })
    return
  }

  if (type.value.length === 0) {
    Dialog({
      title: t('app.alert'),
      message: t('task.type_required'),
      confirmButtonText: t('app.confirm')
    })
    return
  }

  if (period.value.length === 0) {
    Dialog({
      title: t('app.alert'),
      message: t('task.period_required'),
      confirmButtonText: t('app.confirm')
    })
    return
  }

  loading.value = true
  const jiacn = globalStore.getJiacn
  
  // 准备数据
  const taskData = {
    jiacn: jiacn,
    type: type.value || '',
    period: period.value || '',
    name: name.value.trim(),
    description: description.value.trim(),
    lunar: lunar.value,
    amount: amount.value || 0,
    remind: remind.value
  }

  // 添加时间数据（如果有有效值）
  if (start_time.value && start_time.value.trim()) {
    const startDate = new Date(start_time.value)
    if (!isNaN(startDate.getTime())) {
      taskData.startTime = utilStore.toTimeStamp(startDate)
    }
  }
  if (end_time.value && end_time.value.trim()) {
    const endDate = new Date(end_time.value)
    if (!isNaN(endDate.getTime())) {
      taskData.endTime = utilStore.toTimeStamp(endDate)
    }
  }

  taskApi.create('/create', taskData, {
    onSuccess: (data) => {
      loading.value = false
      if (data.code === 'E0') {
        Dialog({
          title: t('app.notify'),
          message: data.msg,
          confirmButtonText: t('app.confirm'),
          onConfirm: () => {
            router.go(-1)
          }
        })
      } else {
        Dialog({
          title: t('app.alert'),
          message: data.msg,
          confirmButtonText: t('app.confirm')
        })
      }
    },
    onError: (error) => {
      loading.value = false
      Dialog({
        title: t('app.error'),
        message: t('app.network_error'),
        confirmButtonText: t('app.confirm')
      })
      console.error('创建任务失败:', error)
    }
  })
}

const changeType = (val) => {
  if (val === '1') {
    amountShow.value = false
  } else {
    amountShow.value = true
  }
}

const changePeriod = (val) => {
  if (val === '6') {
    startTimeShow.value = true
    endTimeShow.value = false
    end_time.value = ''
  } else if (val === '0') {
    startTimeShow.value = false
    endTimeShow.value = false
    start_time.value = ''
    end_time.value = ''
  } else {
    startTimeShow.value = true
    endTimeShow.value = true
  }
}

// 生命周期
onMounted(() => {
  globalStore.setTitle(t('app.task_add'))
  globalStore.setShowBack(true)
})
</script>

<style scoped>
.task-add-container {
  padding: 16px;
  min-height: 100vh;
  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
}

.form-card {
  background: white;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 100px);
}

.form-section {
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid #f0f0f0;
}

.form-section:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
  padding-bottom: 0;
}

.section-title {
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 600;
  color: #333;
  display: flex;
  align-items: center;
}

.section-title::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 2px;
  margin-right: 8px;
}

.form-field {
  margin-bottom: 16px;
}

.form-field:last-child {
  margin-bottom: 0;
}

.form-content {
  flex: 1;
  overflow-y: auto;
  max-height: calc(100vh - 220px);
  padding-right: 4px;
}

.form-actions {
  margin-top: 32px;
  padding-top: 20px;
  border-top: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.submit-button {
  height: 48px;
  font-size: 16px;
  font-weight: 600;
  border-radius: 12px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
  transition: all 0.3s ease;
}

.submit-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(102, 126, 234, 0.4);
}

.submit-button:active {
  transform: translateY(0);
}

/* 响应式调整 */
@media (max-width: 375px) {
  .task-add-container {
    padding: 12px;
  }
  
  .form-card {
    padding: 20px;
  }
  
  .form-section {
    margin-bottom: 20px;
    padding-bottom: 16px;
  }
  
  .section-title {
    font-size: 15px;
    margin-bottom: 12px;
  }
}
</style>
