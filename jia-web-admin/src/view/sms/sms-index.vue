<template>
  <div id="echarts_div">
    <Card>
      <div class="charts_wrap">
        <Col span="14">
          <chart-bar style="height: 300px;" :value="smsBuyData" text="短信购买情况"/>
        </Col>
        <Col span="10">
          <Card class="choice_time"  v-show="$constant.findRole('sms_receive')">
            <Form label-position="left"  :label-width="120" ref="buyStand_form" :model="buyStand_form">
              <FormItem label="购买起始时间">
                <DatePicker  type="date" placeholder="起始时间"  @on-change="buyTimeChange" v-model="buyStand_form.timeStart"></DatePicker>
              </FormItem>
              <FormItem label="购买结束时间">
                <DatePicker
                  type="date"
                  placeholder="结束时间"
                  :start-date="buyStand_form.timeEndStartDate"
                  :options="buyStand_form.endTimeOptions"
                  @on-change="buyStand_form.timeEnd=$event"
                  v-model="buyStand_form.timeEnd"
                >
                </DatePicker>
              </FormItem>
              <FormItem>
                <Button type="success" @click="appendSearch('buyStand_form','other')">确定</Button>
              </FormItem>
              <FormItem label="时间段选择">
                <Tag @click.native="appendSearch('buyStand_form','year')">年度内</Tag>
                <Tag @click.native="appendSearch('buyStand_form','quarter')">季度内</Tag>
                <Tag @click.native="appendSearch('buyStand_form','month')">月内</Tag>
              </FormItem>
            </Form>
          </Card>
        </Col>
      </div>
      <div class="charts_wrap">
        <Col span="14">
          <chart-line style="height: 300px;" :value="smsSendData" text="统计每日发送短信数量"/>
        </Col>
        <Col span="10">
          <Card class="choice_time">
            <Form label-position="left"  :label-width="120" ref="smsSend_form" :model="smsSend_form">
              <FormItem label="统计发送起始时间">
                <DatePicker  type="date" placeholder="起始时间"  @on-change="sendStartTimeChange" v-model="smsSend_form.timeStart"></DatePicker>
              </FormItem>
              <FormItem label="统计发送结束时间">
                <DatePicker
                  type="date"
                  :start-date="smsSend_form.timeEndStartDate"
                  placeholder="结束时间"
                  :options="smsSend_form.endTimeOptions"
                  @on-change="smsSend_form.timeEnd=$event"
                  v-model="smsSend_form.timeEnd">
                </DatePicker>
              </FormItem>
              <FormItem>
                <Button type="success" @click="appendSearch('smsSend_form','other')" :disabled="!($constant.findRole('sms_receive'))">确定</Button>
              </FormItem>
              <FormItem label="时间段选择">
                <Tag @click.native="appendSearch('smsSend_form','oneWeek')">一周内</Tag>
                <Tag @click.native="appendSearch('smsSend_form','twoWeek')">两周内</Tag>
              </FormItem>
            </Form>
          </Card>
        </Col>
      </div>
      <div class="charts_wrap">
        <Col span="14">
          <chart-pie style="height: 300px;" :value="smsSendChartData" subtitle="手机号码" text="统计短信发送量"/>
        </Col>
        <Col span="10">
          <Card class="choice_time">
            <Form label-position="left"  :label-width="120" ref="smsSendChart_form" :model="smsSendChart_form">
              <FormItem label="短信发送起始时间">
                <DatePicker  type="date" placeholder="起始时间"  @on-change="sendChartTimeChange" v-model="smsSendChart_form.timeStart"></DatePicker>
              </FormItem>
              <FormItem label="短信发送结束时间">
                <DatePicker
                  type="date"
                  placeholder="结束时间"
                  :start-date="smsSendChart_form.timeEndStartDate"
                  :options="smsSendChart_form.endTimeOptions"
                  @on-change="smsSendChart_form.timeEnd=$event"
                  v-model="smsSendChart_form.timeEnd"
                >
                </DatePicker>
              </FormItem>
              <FormItem>
                <Button type="success" @click="appendSearch('smsSendChart_form','other')">确定</Button>
              </FormItem>
              <FormItem label="时间段选择">
                <Tag @click.native="appendSearch('smsSendChart_form','year')">年度内</Tag>
                <Tag @click.native="appendSearch('smsSendChart_form','quarter')">季度内</Tag>
                <Tag @click.native="appendSearch('smsSendChart_form','month')">月内</Tag>
              </FormItem>
            </Form>
          </Card>
        </Col>
      </div>
    </Card>
  </div>
</template>
<script>
import { ChartBar, ChartLine, ChartPie } from '_c/charts'
import { SmsBuyList, SmsSendChart, getSmsSend } from '@/api/data'
export default {
  components: {
    ChartPie,
    ChartBar,
    ChartLine
  },
  data () {
    return {
      dataTime: '',
      year: '',
      quarter: '',
      month: '',
      oneWeek: '',
      twoWeek: '',
      // 购买情况集合
      buyStand_form: {
        timeEndStartDate: null,
        endTimeOptions: {}, // 结束日期设置
        timeStart: '',
        timeEnd: ''
      },
      smsBuyData: {},
      // 每日发送集合
      smsSend_form: {
        timeEndStartDate: null,
        endTimeOptions: {}, // 结束日期设置
        timeStart: '',
        timeEnd: ''
      },
      smsSendData: {},
      // 统计短信发送量
      smsSendChart_form: {
        timeEndStartDate: null,
        endTimeOptions: {}, // 结束日期设置
        timeStart: '',
        timeEnd: ''
      },
      smsSendChartData: []
    }
  },
  methods: {
    initVue () {
      var dataArr = this.getData()
      this.year = dataArr[0]
      this.quarter = dataArr[1]
      this.month = dataArr[2]
      this.dataTime = dataArr[3]
      this.oneWeek = dataArr[4]
      this.twoWeek = dataArr[5]
      this.buyStand_form.timeEndStartDate = new Date(this.$constant.GetDateStr(0))
      this.smsSend_form.timeEndStartDate = new Date(this.$constant.GetDateStr(0))
      this.smsSendChart_form.timeEndStartDate = new Date(this.$constant.GetDateStr(0))
      this.appendSearch('buyStand_form', 'year')
      this.appendSearch('smsSend_form', 'twoWeek')
      this.appendSearch('smsSendChart_form', 'year')
    },
    // 控制选择时间
    sendChartTimeChange: function (e) { // 设置开始时间
      this.smsSendChart_form.starttime = e
      this.smsSendChart_form.timeEndStartDate = e ? new Date(e) : new Date(this.$constant.GetDateStr(0))
      this.smsSendChart_form.timeEnd = ''
      this.smsSendChart_form.endTimeOptions = {
        disabledDate: date => {
          let startTime = this.smsSendChart_form.starttime ? new Date(this.smsSendChart_form.starttime).valueOf() : ''
          return date && (date.valueOf() < startTime)
        }
      }
    },
    buyTimeChange: function (e) { // 设置开始时间
      this.buyStand_form.starttime = e
      this.buyStand_form.timeEndStartDate = e ? new Date(e) : new Date(this.$constant.GetDateStr(0))
      this.buyStand_form.timeEnd = ''
      this.buyStand_form.endTimeOptions = {
        disabledDate: date => {
          let startTime = this.buyStand_form.starttime ? new Date(this.buyStand_form.starttime).valueOf() : ''
          return date && (date.valueOf() < startTime)
        }
      }
    },
    sendStartTimeChange: function (e) { // 设置开始时间
      this.smsSend_form.starttime = e
      this.smsSend_form.timeEndStartDate = e ? new Date(e) : new Date(this.$constant.GetDateStr(0))
      this.smsSend_form.timeEnd = ''
      this.smsSend_form.endTimeOptions = {
        disabledDate: date => {
          let startTime = this.smsSend_form.starttime ? new Date(this.smsSend_form.starttime).valueOf() : ''
          let MaxTime = this.smsSend_form.starttime ? new Date(this.$constant.GetDataCount(e, 14)).valueOf() : ''
          return date && (date.valueOf() < startTime || date.valueOf() > MaxTime)
        }
      }
    },
    // 获取短信购买列表
    getSmsBuyList (obj, search) {
      const data = {
        'search': search
      }
      SmsBuyList(data).then(res => {
        var result = res.data.data
        if (obj === 'buyStand_form') {
          var newSmsBuyData = {}
          result.sort().reverse().forEach((val, i) => {
            var time = this.$constant.formatDate(val.time)
            var number = val.number
            if (i === 0) {
              newSmsBuyData[time] = number
            } else {
              for (var key in newSmsBuyData) {
                if (key === time) {
                  var oldNumber = newSmsBuyData[time]
                  newSmsBuyData[time] = oldNumber + number
                } else {
                  newSmsBuyData[time] = number
                }
              }
            }
          })
          this.smsBuyData = newSmsBuyData
        } else {}
      })
    },
    // 统计短信发送量
    SmsSendChartList (obj, search) {
      const data = {
        'search': search
      }
      SmsSendChart(data).then(res => {
        var result = res.data.data
        if (obj === 'smsSendChart_form') {
          var newSmsSendChartData = []
          var otherNum = 0
          result.forEach((val, i) => {
            var name = val.mobile
            var value = val.num
            if (i < 9) {
              var t = { name: name, value: value }
              newSmsSendChartData.push(t)
            } else {
              otherNum = otherNum + value
            }
          })
          var othet = { name: '其他合计', value: otherNum }
          newSmsSendChartData.push(othet)
          this.smsSendChartData = newSmsSendChartData
        } else {}
      })
    },
    // 短信发送列表
    SmsSendList (obj, search) {
      const data = {
        'search': search
      }
      getSmsSend(data).then(res => {
        var result = res.data.data
        if (obj === 'smsSend_form') {
          var newSmsSendData = {}
          var startTime = this.$constant.formatDate(JSON.parse(search)['timeStart'])
          var endTime = this.$constant.formatDate(JSON.parse(search)['timeEnd'])
          newSmsSendData[startTime] = 0
          for (var i = 1; this.$constant.GetDataCount(startTime, i) !== endTime; i++) {
            var newDate = this.$constant.GetDataCount(startTime, i)
            newSmsSendData[newDate] = 0
          }
          newSmsSendData[endTime] = 0
          result.sort().reverse().forEach((val, i) => {
            var time = this.$constant.formatDate(val.time)
            var number = 1
            for (var key in newSmsSendData) {
              if (key === time) {
                var oldNumber = newSmsSendData[time]
                newSmsSendData[time] = oldNumber + number
              } else {}
            }
          })
          // console.log(newSmsSendData)
          this.smsSendData = newSmsSendData
        } else {}
      })
    },
    // 获取日期
    getData () {
      var dataTime = this.$constant.stampDateTime(this.$constant.GetDateStr(0))
      var month = this.$constant.stampDateTime(this.$constant.GetDateStr(-30))
      var year = this.$constant.stampDateTime(this.$constant.GetDateStr(-365))
      var quarter = this.$constant.stampDateTime(this.$constant.GetDateStr(-90))
      var oneWeek = this.$constant.stampDateTime(this.$constant.GetDateStr(-7))
      var twoWeek = this.$constant.stampDateTime(this.$constant.GetDateStr(-14))
      var dataArr = [year, quarter, month, dataTime, oneWeek, twoWeek]
      return dataArr
    },
    // 生成选择条件
    appendSearch (obj, type) {
      var Tobj = eval('this.' + obj)
      var searchObj = {}
      var check = false
      searchObj['timeEnd'] = this.dataTime
      if (type === 'year') {
        searchObj['timeStart'] = this.year
      } else if (type === 'quarter') {
        searchObj['timeStart'] = this.quarter
      } else if (type === 'month') {
        searchObj['timeStart'] = this.month
      } else if (type === 'oneWeek') {
        searchObj['timeStart'] = this.oneWeek
      } else if (type === 'twoWeek') {
        searchObj['timeStart'] = this.twoWeek
      } else if (type === 'other') {
        var timeStart = Tobj.timeStart
        var timeEnd = Tobj.timeEnd
        if (timeStart && timeEnd) {
          searchObj['timeStart'] = this.$constant.stampDateTime(timeStart)
          searchObj['timeEnd'] = this.$constant.stampDateTime(timeEnd)
        } else {
          check = true
          this.$Message.error('时间选择不完全')
        }
      } else {}
      if (check) return
      if (obj === 'buyStand_form') {
        this.getSmsBuyList(obj, JSON.stringify(searchObj))
      } else if (obj === 'smsSend_form') {
        this.SmsSendList(obj, JSON.stringify(searchObj))
      } else if (obj === 'smsSendChart_form') {
        this.SmsSendChartList(obj, JSON.stringify(searchObj))
      } else {}
    }
  },
  // 调用
  mounted () {
    this.initVue()
  }
}
</script>

<style scoped>
  #echarts_div /deep/ .ivu-card-body{
    min-height: 600px;
  }
  .choice_time.ivu-card{
    max-width: 80%;
  }
  .choice_time /deep/ .ivu-card-body{
    min-height: 150px!important;
  }
  .charts_wrap{
    margin-top: 15px;
    display: flex;
  }
</style>
