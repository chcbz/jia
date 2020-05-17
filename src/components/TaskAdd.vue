<template>
<div>
  <group label-width="4.5em" label-margin-right="2em" label-align="right">
    <popup-picker :title="$t('task.type')" :data="typeList" :columns="1" v-model="type" @on-change="changeType" show-name></popup-picker>
    <popup-picker :title="$t('task.period')" :data="periodList" :columns="1" v-model="period" @on-change="changePeriod" show-name></popup-picker>
    <x-input :title="$t('task.name')" v-model="name" text-align="right"></x-input>
    <x-textarea :title="$t('task.description')" v-model="description" :show-counter="false" :rows="3"></x-textarea>
    <datetime v-model="start_time" :max-year="2100" format="YYYY-MM-DD HH:mm" :minute-list="['00', '15', '30', '45']" :title="$t('task.start_time')" v-show="startTimeShow"></datetime>
    <datetime v-model="end_time" :max-year="2100" format="YYYY-MM-DD HH:mm" :minute-list="['00', '15', '30', '45']" :title="$t('task.end_time')" v-show="endTimeShow"></datetime>
    <x-switch :title="$t('task.lunar')" :value-map="['0', '1']" v-model="lunar"></x-switch>
    <x-input :title="$t('task.amount')" v-model="amount" text-align="right" type="tel" :max="6" v-show="amountShow"></x-input>
    <x-switch :title="$t('task.remind')" :value-map="['0', '1']" v-model="remind"></x-switch>
    <br/>
    <x-button action-type="button" type="primary" @click.native="doAdd">{{$t('app.save')}}</x-button>
  </group>
</div>
</template>

<script>
import { Group, PopupPicker, Datetime, XInput, XTextarea, XSwitch, XButton, AlertModule } from 'vux'

export default {
  created: function () {
    this.$store.commit('global/setMenu', {
      menus: [],
      event: this
    })
    this.$store.commit('global/setTitle', this.$t('app.task_add'))
    this.$store.commit('global/setShowBack', true)
  },
  methods: {
    doAdd: function () {
      var baseUrl = this.$store.state.api.baseUrl
      var token = this.$store.state.api.token()
      var jiacn = this.$store.state.global.user.jiacn
      this.$http.post(baseUrl + '/task/create', {
        jiacn: jiacn,
        type: this.type[0],
        period: this.period[0],
        crond: this.crond,
        name: this.name,
        description: this.description,
        startTime: this.$store.state.util.toTimeStamp(new Date(this.start_time)),
        endTime: this.$store.state.util.toTimeStamp(new Date(this.end_time)),
        lunar: this.lunar,
        amount: this.amount,
        remind: this.remind
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          AlertModule.show({title: this.$t('app.notify'), content: res.data.msg})
          this.$router.go(-1)
        } else {
          AlertModule.show({title: this.$t('app.alert'), content: res.data.msg})
        }
      })
    },
    changeType: function (val) {
      if (val[0] === '1') {
        this.amountShow = false
      } else {
        this.amountShow = true
      }
    },
    changePeriod: function (val) {
      if (val[0] === '6') {
        this.startTimeShow = true
        this.endTimeShow = false
        this.end_time = ''
      } else if (val[0] === '0') {
        this.startTimeShow = false
        this.endTimeShow = false
        this.start_time = ''
        this.end_time = ''
      } else {
        this.startTimeShow = true
        this.endTimeShow = true
      }
    }
  },
  data () {
    return {
      type: [],
      period: [],
      crond: '',
      name: '',
      description: '',
      start_time: '',
      startTimeShow: false,
      end_time: '',
      endTimeShow: false,
      amount: null,
      amountShow: false,
      remind: '1',
      lunar: '0',
      typeList: [{
        name: '常规提醒',
        value: '1'
      }, {
        name: '目标',
        value: '2'
      }, {
        name: '还款计划',
        value: '3'
      }, {
        name: '固定收入',
        value: '4'
      }],
      periodList: [{
        name: '长期',
        value: '0'
      }, {
        name: '每年',
        value: '1'
      }, {
        name: '每月',
        value: '2'
      }, {
        name: '每周',
        value: '3'
      }, {
        name: '每日',
        value: '5'
      }, {
        name: '每小时',
        value: '11'
      }, {
        name: '每分钟',
        value: '12'
      }, {
        name: '每秒',
        value: '13'
      }, {
        name: '指定日期',
        value: '6'
      }]
    }
  },
  components: {
    Group,
    PopupPicker,
    Datetime,
    XInput,
    XTextarea,
    XSwitch,
    XButton
  }
}
</script>
<style scoped>
.picker-buttons {
  margin: 0 15px;
}
</style>
