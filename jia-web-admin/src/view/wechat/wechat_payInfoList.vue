<template>
  <div>
    <Card>
      <Button style="margin: 10px 0;" type="success" @click="handleCreate">创建支付平台</Button>
      <tables ref="tables" editable  border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <wechat-pay ref="WechatPay" @operate="ParentOperate"></wechat-pay>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import WechatPay from './modal/wechat-pay'
import { WxPayInfoList, createWxPay, updateWxPay, delWxPay } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    WechatPay
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '支付平台名称',
          key: 'name'
        },
        { title: '支付平台帐号',
          key: 'account'
        },
        { title: '开发者ID',
          key: 'appId'
        },
        { title: '商户ID',
          key: 'mchId'
        },
        { title: '状态',
          key: 'status',
          render: (h, params) => {
            var data = params.row
            var status = data.status
            var result = this.$constant.getvalue('WxPayStatus', status)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          width: 140,
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var update = h('Button', {
              props: {
                type: 'info',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleUpdate(data)
                }
              }
            }, '修改')
            var del = h('Button', {
              props: {
                type: 'error',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleDelete(data)
                }
              }
            }, '删除')
            return h('div', {
              class: {
                table_operate: true
              }
            }, [update, del])
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 创建微信支付平台
    handleCreate () {
      this.$refs.WechatPay.$refs.WxPay_form.resetFields()
      this.$refs.WechatPay.WxPay_title = '创建微信支付平台'
      this.$refs.WechatPay.WxPay_modal = true
      this.$refs.WechatPay.WxPay_acid = ''
      this.$refs.WechatPay.WxPay_form.name = ''
      this.$refs.WechatPay.WxPay_form.account = ''
      this.$refs.WechatPay.WxPay_form.country = ''
      this.$refs.WechatPay.WxPay_form.province = ''
      this.$refs.WechatPay.WxPay_form.city = ''
      this.$refs.WechatPay.WxPay_form.username = ''
      this.$refs.WechatPay.WxPay_form.password = ''
      this.$refs.WechatPay.WxPay_form.appId = ''
      this.$refs.WechatPay.WxPay_form.subappId = ''
      this.$refs.WechatPay.WxPay_form.mchId = ''
      this.$refs.WechatPay.WxPay_form.mchKey = ''
      this.$refs.WechatPay.WxPay_form.subMchId = ''
      this.$refs.WechatPay.WxPay_form.notifyUrl = ''
      this.$refs.WechatPay.WxPay_form.tradeType = ''
      this.$refs.WechatPay.WxPay_form.signType = ''
      this.$refs.WechatPay.WxPay_form.keyPath = ''
      this.$refs.WechatPay.WxPay_form.keyContent = ''
      this.$refs.WechatPay.WxPay_judge = 'create'
    },
    // 修改微信支付平台
    handleUpdate (x) {
      this.$refs.WechatPay.$refs.WxPay_form.resetFields()
      this.$refs.WechatPay.WxPay_title = '修改微信支付平台'
      this.$refs.WechatPay.WxPay_modal = true
      this.$refs.WechatPay.WxPay_acid = x.acid
      this.$refs.WechatPay.WxPay_form.name = x.name
      this.$refs.WechatPay.WxPay_form.account = x.account
      this.$refs.WechatPay.WxPay_form.country = x.country
      this.$refs.WechatPay.WxPay_form.province = x.province
      this.$refs.WechatPay.WxPay_form.city = x.city
      this.$refs.WechatPay.WxPay_form.username = x.username
      this.$refs.WechatPay.WxPay_form.password = x.password
      this.$refs.WechatPay.WxPay_form.appId = x.appId
      this.$refs.WechatPay.WxPay_form.subappId = x.subappId
      this.$refs.WechatPay.WxPay_form.mchId = x.mchId
      this.$refs.WechatPay.WxPay_form.mchKey = x.mchKey
      this.$refs.WechatPay.WxPay_form.subMchId = x.subMchId
      this.$refs.WechatPay.WxPay_form.notifyUrl = x.notifyUrl
      this.$refs.WechatPay.WxPay_form.tradeType = x.tradeType
      this.$refs.WechatPay.WxPay_form.signType = x.signType
      this.$refs.WechatPay.WxPay_form.keyPath = x.keyPath
      this.$refs.WechatPay.WxPay_form.keyContent = x.keyContent
      this.$refs.WechatPay.WxPay_judge = 'update'
    },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.WechatPay.WxPay_judge
      var acid = this.$refs.WechatPay.WxPay_acid
      var name = this.$refs.WechatPay.WxPay_form.name
      var account = this.$refs.WechatPay.WxPay_form.account
      var country = this.$refs.WechatPay.WxPay_form.country
      var province = this.$refs.WechatPay.WxPay_form.province
      var city = this.$refs.WechatPay.WxPay_form.city
      var username = this.$refs.WechatPay.WxPay_form.username
      var password = this.$refs.WechatPay.WxPay_form.password
      var appId = this.$refs.WechatPay.WxPay_form.appId
      var subappId = this.$refs.WechatPay.WxPay_form.subappId
      var mchId = this.$refs.WechatPay.WxPay_form.mchId
      var mchKey = this.$refs.WechatPay.WxPay_form.mchKey
      var subMchId = this.$refs.WechatPay.WxPay_form.subMchId
      var notifyUrl = this.$refs.WechatPay.WxPay_form.notifyUrl
      var tradeType = this.$refs.WechatPay.WxPay_form.tradeType
      var signType = this.$refs.WechatPay.WxPay_form.signType
      var keyPath = this.$refs.WechatPay.WxPay_form.keyPath
      var keyContent = this.$refs.WechatPay.WxPay_form.keyContent
      var data = {
        'name': name,
        'account': account,
        'country': country,
        'province': province,
        'city': city,
        'username': username,
        'password': password,
        'appId': appId,
        'subappId': subappId,
        'mchId': mchId,
        'mchKey': mchKey,
        'subMchId': subMchId,
        'notifyUrl': notifyUrl,
        'tradeType': tradeType,
        'signType': signType,
        'keyPath': keyPath,
        'keyContent': keyContent
      }
      if (judge === 'create') {
        this.$refs.WechatPay.$refs.WxPay_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createWxPay(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.WechatPay.WxPay_modal = false
                this.getWxPayInfoList(1)
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else if (judge === 'update') {
        this.$refs.WechatPay.$refs.WxPay_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['acid'] = acid
            updateWxPay(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.WechatPay.WxPay_modal = false
                this.getWxPayInfoList(1)
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else {}
    },
    // 删除微信支付平台
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除已记录的支付平台'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.acid
      delWxPay(id).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.getWxPayInfoList(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    getWxPayInfoList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      WxPayInfoList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.getWxPayInfoList(page)
    }
  },
  mounted () {
    this.getWxPayInfoList(1)
  }
}
</script>
