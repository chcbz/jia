<template>
  <div style="height: 100%">
    <div class="calendar-container" v-if="showCalendar">
      <div class="calendar-header">
        <var-button @click="prevMonth">&lt;</var-button>
        <h2>{{ currentMonth }}</h2>
        <var-button @click="nextMonth">&gt;</var-button>
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
                'has-tasks': day.hasTasks
              }
            ]"
            @click="selectCalendarDay(day)"
          >
            <div class="day-number">{{ day.day }}</div>
            <div v-if="day.hasTasks" class="task-indicator"></div>
          </div>
        </div>
      </div>
    </div>

    <var-dialog v-model="taskDetailShow">
      <div class="task-detail">
        <div class="task-item" v-for="item in detail" :key="item.id">
          <div class="task-header">
            <h3>{{ item.title }}</h3>
            <span class="task-amount">{{ item.meta.source }}</span>
          </div>
          <div class="task-desc">{{ item.desc }}</div>
          <div class="task-meta">
            <span>{{ item.meta.date }}</span>
            <span>{{ item.meta.other }}</span>
          </div>
        </div>
      </div>
    </var-dialog>

    <var-list>
      <var-cell
        :title="typeDict(item.type) + item.name"
        :value="item.amount"
        v-for="item in listPlan"
        :key="item.id"
        @click="doShowDetail(item)"
        clickable
      >
      </var-cell>
    </var-list>
  </div>
</template>

<script>
import { useGlobalStore } from '../stores/global';
import { useApiStore } from '../stores/api';
import { useUtilStore } from '../stores/util';
import dayjs from 'dayjs';
import { taskApi } from '../composables/useHttp';

export default {
  created() {
    const globalStore = useGlobalStore();
    globalStore.setMenu({
      menus: [
        {
          key: 'add',
          value: this.$t('task.add'),
          fn: () => {
            this.$router.push({ name: 'TaskAdd' });
          }
        },
        {
          key: 'list',
          value: this.$t('app.task_list'),
          fn: () => {
            this.$router.push({ name: 'TaskList' });
          }
        },
        {
          key: 'history',
          value: this.$t('app.task_history'),
          fn: () => {
            this.$router.push({ name: 'TaskHistory' });
          }
        }
      ],
      event: this
    });
    globalStore.setTitle(this.$t('app.title'));
    globalStore.setShowBack(false);
    this.fetchTasks();
  },
  computed: {
    currentMonth() {
      return this.currentDate.format('YYYY年MM月');
    },
    calendarDays() {
      const startOfMonth = this.currentDate.startOf('month');
      const endOfMonth = this.currentDate.endOf('month');
      const startDay = startOfMonth.day();
      const daysInMonth = endOfMonth.date();

      const daysArray = [];

      // 上个月的最后几天
      const prevMonthDays = startDay;
      for (let i = prevMonthDays - 1; i >= 0; i--) {
        const date = startOfMonth.subtract(i + 1, 'day');
        daysArray.push({
          date: date.format('YYYY-MM-DD'),
          day: date.date(),
          isCurrentMonth: false,
          isToday: date.isSame(dayjs(), 'day'),
          hasTasks: this.hasTasksForDate(date)
        });
      }

      // 当前月的天数
      for (let i = 1; i <= daysInMonth; i++) {
        const date = startOfMonth.date(i);
        daysArray.push({
          date: date.format('YYYY-MM-DD'),
          day: date.date(),
          isCurrentMonth: true,
          isToday: date.isSame(dayjs(), 'day'),
          hasTasks: this.hasTasksForDate(date)
        });
      }

      // 下个月的前几天
      const remainingCells = 42 - daysArray.length;
      for (let i = 1; i <= remainingCells; i++) {
        const date = endOfMonth.add(i, 'day');
        daysArray.push({
          date: date.format('YYYY-MM-DD'),
          day: date.date(),
          isCurrentMonth: false,
          isToday: date.isSame(dayjs(), 'day'),
          hasTasks: this.hasTasksForDate(date)
        });
      }

      return daysArray;
    }
  },
  methods: {
    hasTasksForDate(date) {
      return this.highlightDates.includes(date.format('YYYY-MM-DD'));
    },
    prevMonth() {
      this.currentDate = this.currentDate.subtract(1, 'month');
      this.fetchTasks();
    },
    nextMonth() {
      this.currentDate = this.currentDate.add(1, 'month');
      this.fetchTasks();
    },
    selectCalendarDay(day) {
      if (!day.isCurrentMonth) return;
      this.value = day.date;
      this.onChange(day.date);
    },
    onChange(val) {
      const valTimeStart = dayjs(val).startOf('day').unix();
      const valTimeEnd = dayjs(val).endOf('day').unix();
      const globalStore = useGlobalStore();
      var jiacn = globalStore.getJiacn;
      taskApi.search('/item/search', {
        search: {
          jiacn: jiacn,
          status: 1,
          timeStart: valTimeStart,
          timeEnd: valTimeEnd
        }
      }).then((res) => {
        this.listPlan = res.data;
        this.totalMoney = 0;
      });
    },
    fetchTasks() {
      const firstDay = this.currentDate.startOf('month');
      const lastDay = this.currentDate.endOf('month');
      const globalStore = useGlobalStore();
      var jiacn = globalStore.getJiacn;

      taskApi.search('/item/search', {
        search: {
          jiacn: jiacn,
          timeStart: firstDay.unix(),
          timeEnd: lastDay.unix()
        }
      }).then((res) => {
        this.highlightDates = res.data.map((item) =>
          dayjs.unix(item.executeTime).format('YYYY-MM-DD')
        );
      });
    },
    doShowDetail(item) {
      const utilStore = useUtilStore();
      taskApi.getById('/get', item.planId).then((res) => {
        this.detail = [];
        let period = {
          0: '长期',
          1: '每年',
          2: '每月',
          3: '每周',
          5: '每日',
          11: '每小时',
          12: '每分钟',
          13: '每秒',
          6: '指定日期'
        };
        let taskItem = {
          id: item.id,
          title: item.name,
          desc: item.description,
          meta: {
            source: item.type > 1 ? '￥' + item.amount : '',
            date:
              item.type > 1
                ? dayjs.unix(item.executeTime).format('YYYY-MM-DD')
                : dayjs.unix(res.data.startTime).format('YYYY-MM-DD') +
                  ' ~ ' +
                  dayjs.unix(res.data.endTime).format('YYYY-MM-DD'),
            other: item.crond == null ? period[item.period] : item.crond
          }
        };
        this.detail.push(taskItem);
        this.taskDetailShow = true;
      });
    },
    typeDict(key) {
      let value;
      switch (key) {
        case 3:
          value = this.$t('task.type_pay');
          break;
        default:
          value = this.$t('task.type_notify');
      }
      return '【' + value + '】';
    }
  },
  data() {
    return {
      show: true,
      showCalendar: true,
      currentDate: dayjs(),
      weekdays: ['日', '一', '二', '三', '四', '五', '六'],
      value: dayjs().format('YYYY-MM-DD'),
      highlightDates: [],
      listPlan: [],
      detail: [],
      taskDetailShow: false
    };
  }
};
</script>

<style scoped>
.calendar-container {
  background: #fff;
  border-radius: 8px;
  padding: 15px;
  margin-bottom: 15px;
  overflow-y: auto;
}

.calendar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.calendar-header h2 {
  margin: 0;
  font-size: 18px;
}

.calendar-grid {
  display: flex;
  flex-direction: column;
}

.calendar-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  text-align: center;
  font-weight: bold;
  margin-bottom: 10px;
}

.calendar-days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 5px;
}

.day {
  aspect-ratio: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  cursor: pointer;
  position: relative;
}

.day.current-month {
  background: #f8f9fa;
}

.day.today {
  background: #e3f2fd;
}

.day.has-tasks .task-indicator {
  width: 6px;
  height: 6px;
  background: #42b983;
  border-radius: 50%;
  margin-top: 2px;
}

.task-detail {
  padding: 15px;
}

.task-item {
  margin-bottom: 15px;
  padding: 15px;
  background: #fff;
  border-radius: 8px;
}

.task-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.task-header h3 {
  margin: 0;
  font-size: 16px;
}

.task-amount {
  color: #ff6b6b;
  font-weight: bold;
}

.task-desc {
  color: #666;
  margin-bottom: 8px;
  font-size: 14px;
}

.task-meta {
  display: flex;
  justify-content: space-between;
  color: #999;
  font-size: 12px;
}

.date-picker {
  padding: 15px;
  background: #fff;
  margin-bottom: 15px;
  border-radius: 8px;
}

.date-input {
  width: 100%;
  padding: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
}
</style>
