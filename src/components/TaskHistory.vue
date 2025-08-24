<template>
<div>
  <var-action-sheet 
    :actions="opMenu" 
    v-model="showOpMenu" 
    @select="onClickOpMenu">
  </var-action-sheet>

  <div class="task-list">
    <div class="task-item" v-for="item in list" :key="item.id" @click="doShowOpMenu(item)">
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
</div>
</template>

<script>
import { useGlobalStore } from '../stores/global'
import { useApiStore } from '../stores/api'
import { useUtilStore } from '../stores/util'
import dayjs from 'dayjs'

export default {
  created: function () {
    const globalStore = useGlobalStore()
    const apiStore = useApiStore()
    const utilStore = useUtilStore()
    
    globalStore.setMenu({
      menus: [],
      event: this
    })
    globalStore.setTitle(this.$t('app.task_history'))
    globalStore.setShowBack(true)
    var baseUrl = apiStore.baseUrl
    var jiacn = globalStore.getJiacn
    this.$http.post(baseUrl + '/task/search', {
      search: {
        jiacn: jiacn,
        historyFlag: 1
      }
    }).then(res => {
      this.list = []
      res.data.data.forEach(element => {
        let taskItem = {
          id: element.id,
          title: element.name,
          desc: element.description,
          meta: {
            source: '￥' + element.amount,
            date: dayjs(utilStore.fromTimeStamp(element.startTime)).format('YYYY-MM-DD') + ' - ' + 
                  dayjs(utilStore.fromTimeStamp(element.endTime)).format('YYYY-MM-DD'),
            other: element.crond
          }
        }
        this.list.push(taskItem)
      })
    })
  },
  methods: {
    doShowOpMenu: function (item) {
      this.selectId = item.id
      this.showOpMenu = true
    },
    onClickOpMenu: function (key) {
      if (key === 'del') {
        const _this = this
        Dialog.confirm({
          title: _this.$t('task.del_alert'),
          onConfirm: () => {
            const apiStore = useApiStore()
            var baseUrl = apiStore.baseUrl
            _this.$http.get(baseUrl + '/task/delete', {
              params: {
                id: _this.selectId
              }
            }).then(res => {
              if (res.data.code === 'E0') {
                Dialog({
                  title: _this.$t('app.notify'),
                  message: res.data.msg,
                  confirmButtonText: _this.$t('app.confirm'),
                  onConfirm: () => {
                    _this.$router.go(0)
                  }
                })
              } else {
                Dialog({
                  title: _this.$t('app.alert'),
                  message: res.data.msg,
                  confirmButtonText: _this.$t('app.confirm')
                })
              }
            })
          }
        })
      }
    }
  },
  data () {
    return {
      list: [],
      opMenu: [
        { name: 'del', text: this.$t('app.del') }
      ],
      showOpMenu: false,
      selectId: 0
    }
  },
}
</script>

<style scoped>
.task-list {
  padding: 10px;
}
.task-item {
  margin-bottom: 15px;
  padding: 15px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
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
</style>
