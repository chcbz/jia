<template>
<div style="height:100%;">
  <div v-transfer-dom>
    <x-dialog v-model="taskDetailShow" hide-on-blur>
      <panel :list="detail" type="4"></panel>
    </x-dialog>
  </div>
  <inline-calendar
  ref="calendar"
  @on-change="onChange"
  @on-view-change="onViewChange"
  class="inline-calendar-demo"
  :show.sync="show"
  v-model="value"
  :render-function="buildSlotFn"
  :start-date="startDate"
  :end-date="endDate"
  :highlight-weekend="highlightWeekend">
  </inline-calendar>
  <group>
    <cell :title="typeDict(item.type) + item.name" :value="item.amount" v-for="item in listPlan" v-bind:key="item.id" @click.native="doShowDetail(item)" is-link></cell>
  </group>
</div>
</template>

<script>
import { Panel, ViewBox, XHeader, XDialog, TransferDom, InlineCalendar, Group, Cell, CellFormPreview, dateFormat } from 'vux'

export default {
  created: function () {
    this.$store.commit('global/setMenu', {
      menus: [{
        key: 'add',
        value: this.$t('task.add'),
        fn: function () {
          this.$router.push({name: 'TaskAdd'})
        }
      }, {
        key: 'list',
        value: this.$t('app.task_list'),
        fn: function () {
          this.$router.push({name: 'TaskList'})
        }
      }, {
        key: 'history',
        value: this.$t('app.task_history'),
        fn: function () {
          this.$router.push({name: 'TaskHistory'})
        }
      }],
      event: this
    })
    this.$store.commit('global/setTitle', this.$t('app.title'))
    this.$store.commit('global/setShowBack', false)
  },
  methods: {
    onChange (val) {
      var valTimeStart = new Date(val + ' 00:00:00').getTime() / 1000
      var valTimeEnd = new Date(val + ' 23:59:59').getTime() / 1000
      var baseUrl = this.$store.state.api.baseUrl
      var token = this.$store.state.api.token()
      var jiacn = this.$store.state.global.user.jiacn
      this.$http.post(baseUrl + '/task/item/search', {
        search: JSON.stringify({
          jiacn: jiacn,
          status: 1,
          timeStart: valTimeStart,
          timeEnd: valTimeEnd
        })
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        this.listPlan = res.data.data
        this.totalMoney = 0
        // res.data.data.forEach(element => {
        //   let taskItem = {
        //     label: element.name,
        //     value: element.amount
        //   }
        //   this.listPlan.push(taskItem)
        //   this.totalMoney = (this.totalMoney * 1000 + element.amount * 1000) / 1000
        // })
      })
    },
    onViewChange (val, count) {
      var baseUrl = this.$store.state.api.baseUrl
      var token = this.$store.state.api.token()
      var firstTime = this.$store.state.util.toTimeStamp(new Date(val.firstCurrentMonthDate + ' 00:00:00'))
      var lastTime = this.$store.state.util.toTimeStamp(new Date(val.lastCurrentMonthDate + ' 23:59:59'))
      var jiacn = this.$store.state.global.user.jiacn
      this.$http.post(baseUrl + '/task/item/search', {
        search: JSON.stringify({
          jiacn: jiacn,
          timeStart: firstTime,
          timeEnd: lastTime
        })
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        var redDate = []
        res.data.data.forEach(element => {
          redDate.push(dateFormat(new Date(element.time * 1000), 'YYYY-MM-DD'))
        })
        this.buildSlotFn = (line, index, data) => {
          return redDate.includes(data.formatedDate) ? '<div style="font-size:12px;text-align:center;"><span style="display:inline-block;width:5px;height:5px;background-color:red;border-radius:50%;"></span></div>' : '<div style="height:19px;"></div>'
        }
        this.onChange(this.value)
      })
    },
    doShowDetail (item) {
      var baseUrl = this.$store.state.api.baseUrl
      var token = this.$store.state.api.token()
      this.$http.get(baseUrl + '/task/get', {
        params: {
          id: item.planId,
          access_token: token
        }
      }).then(res => {
        this.detail = []
        let period = { 0: '长期', 1: '每年', 2: '每月', 3: '每周', 5: '每日', 11: '每小时', 12: '每分钟', 13: '每秒', 6: '指定日期' }
        let taskItem = {
          id: item.id,
          title: item.name,
          desc: item.description,
          meta: {
            source: item.type > 1 ? '￥' + item.amount : '',
            date: item.type > 1 ? dateFormat(this.$store.state.util.fromTimeStamp(item.time), 'YYYY-MM-DD') : dateFormat(this.$store.state.util.fromTimeStamp(res.data.data.startTime), 'YYYY-MM-DD') + ' ~ ' + dateFormat(this.$store.state.util.fromTimeStamp(res.data.data.endTime), 'YYYY-MM-DD'),
            other: item.crond == null ? period[item.period] : item.crond
          }
        }
        this.detail.push(taskItem)
        this.taskDetailShow = true
      })
    },
    typeDict (key) {
      let value
      switch (key) {
        case 3:
          value = this.$t('task.type_pay')
          break
        default:
          value = this.$t('task.type_notify')
      }
      return '【' + value + '】'
    }
  },
  data () {
    return {
      show: true,
      value: dateFormat(new Date(), 'YYYY-MM-DD'),
      startDate: '',
      endDate: '',
      highlightWeekend: true,
      buildSlotFn: () => '',
      listPlan: [],
      totalMoney: '',
      index: 0,
      detail: [],
      taskDetailShow: false
    }
  },
  directives: {
    TransferDom
  },
  components: {
    Panel,
    ViewBox,
    XHeader,
    XDialog,
    InlineCalendar,
    Group,
    Cell,
    CellFormPreview
  }
}
</script>

<style lang="less" scoped>
.inline-calendar-demo {
  background: rgba(255,255,255,0.9);
}
</style>
