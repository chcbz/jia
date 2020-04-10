<template>
  <div>
    <Card>
      <Button style="margin: 10px 0;" type="success" @click="handleCreate">新建公众号</Button>
      <tables ref="tables" editable  border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <wechat-mp ref="WechatMp" @operate="ParentOperate"></wechat-mp>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import WechatMp from './modal/wechat-mp'
import { WxMpList, createWxMp, updateWxMp, delWxMp } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    WechatMp
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '公众号名称',
          key: 'name'
        },
        { title: '公众号帐号',
          key: 'account'
        },
        { title: '原始ID',
          key: 'original'
        },
        { title: '认证类别',
          key: 'level',
          render: (h, params) => {
            var data = params.row
            var level = data.level
            var result = this.$constant.getvalue('WxMpLever', level)
            return h('span', result)
          }
        },
        { title: '登录账号',
          key: 'username'
        },
        { title: '开发者ID',
          key: 'appid'
        },
        {
          title: '操作',
          key: 'operate',
          width: 140,
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            return h('div', {
              class: {
                table_operate: true
              }
            }, [
              // h('Button', {
              //   props: {
              //     size: 'small'
              //   },
              //   on: {
              //     click: () => {
              //       var msgid = data.msgid
              //       this.$router.push({ path: 'sms_reply_list/' + msgid })
              //     }
              //   }
              // }, '详细'),
              h('Button', {
                props: {
                  type: 'info',
                  size: 'small'
                },
                on: {
                  click: () => {
                    this.handleUpdate(data)
                  }
                }
              }, '修改'),
              h('Button', {
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
            ])
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 创建微信公众号
    handleCreate () {
      this.$refs.WechatMp.$refs.WxMp_form.resetFields()
      this.$refs.WechatMp.WxMp_title = '创建微信公众号'
      this.$refs.WechatMp.WxMp_modal = true
      this.$refs.WechatMp.WxMp_acid = ''
      this.$refs.WechatMp.WxMp_form.token = ''
      this.$refs.WechatMp.WxMp_form.encodingaeskey = ''
      this.$refs.WechatMp.WxMp_form.level = '1'
      this.$refs.WechatMp.WxMp_form.name = ''
      this.$refs.WechatMp.WxMp_form.account = ''
      this.$refs.WechatMp.WxMp_form.original = ''
      this.$refs.WechatMp.WxMp_form.signature = ''
      this.$refs.WechatMp.WxMp_form.country = ''
      this.$refs.WechatMp.WxMp_form.province = ''
      this.$refs.WechatMp.WxMp_form.city = ''
      this.$refs.WechatMp.WxMp_form.username = ''
      this.$refs.WechatMp.WxMp_form.password = ''
      this.$refs.WechatMp.WxMp_form.appid = ''
      this.$refs.WechatMp.WxMp_form.secret = ''
      this.$refs.WechatMp.WxMp_judge = 'create'
    },
    // 修改微信公众号
    handleUpdate (x) {
      this.$refs.WechatMp.$refs.WxMp_form.resetFields()
      this.$refs.WechatMp.WxMp_title = '修改微信公众号'
      this.$refs.WechatMp.WxMp_modal = true
      this.$refs.WechatMp.WxMp_acid = x.acid
      this.$refs.WechatMp.WxMp_form.token = x.token
      this.$refs.WechatMp.WxMp_form.encodingaeskey = x.encodingaeskey
      this.$refs.WechatMp.WxMp_form.level = (x.level).toString()
      this.$refs.WechatMp.WxMp_form.name = x.name
      this.$refs.WechatMp.WxMp_form.account = x.account
      this.$refs.WechatMp.WxMp_form.original = x.original
      this.$refs.WechatMp.WxMp_form.signature = x.signature
      this.$refs.WechatMp.WxMp_form.country = x.country
      this.$refs.WechatMp.WxMp_form.province = x.province
      this.$refs.WechatMp.WxMp_form.city = x.city
      this.$refs.WechatMp.WxMp_form.username = x.username
      this.$refs.WechatMp.WxMp_form.password = x.password
      this.$refs.WechatMp.WxMp_form.appid = x.appid
      this.$refs.WechatMp.WxMp_form.secret = x.secret
      this.$refs.WechatMp.WxMp_judge = 'update'
    },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.WechatMp.WxMp_judge
      var acid = this.$refs.WechatMp.WxMp_acid
      var token = this.$refs.WechatMp.WxMp_form.token
      var encodingaeskey = this.$refs.WechatMp.WxMp_form.encodingaeskey
      var level = this.$refs.WechatMp.WxMp_form.level
      var name = this.$refs.WechatMp.WxMp_form.name
      var account = this.$refs.WechatMp.WxMp_form.account
      var original = this.$refs.WechatMp.WxMp_form.original
      var signature = this.$refs.WechatMp.WxMp_form.signature
      var country = this.$refs.WechatMp.WxMp_form.country
      var province = this.$refs.WechatMp.WxMp_form.province
      var city = this.$refs.WechatMp.WxMp_form.city
      var username = this.$refs.WechatMp.WxMp_form.username
      var password = this.$refs.WechatMp.WxMp_form.password
      var appid = this.$refs.WechatMp.WxMp_form.appid
      var secret = this.$refs.WechatMp.WxMp_form.secret
      var data = {
        'token': token,
        'encodingaeskey': encodingaeskey,
        'level': level,
        'name': name,
        'account': account,
        'original': original,
        'signature': signature,
        'country': country,
        'province': province,
        'city': city,
        'username': username,
        'password': password,
        'appid': appid,
        'secret': secret
      }
      if (judge === 'create') {
        this.$refs.WechatMp.$refs.WxMp_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createWxMp(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.WechatMp.WxMp_modal = false
                this.getWxMpList(1)
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
        this.$refs.WechatMp.$refs.WxMp_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['acid'] = acid
            updateWxMp(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.WechatMp.WxMp_modal = false
                this.getWxMpList(1)
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
    // 删除公众号
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除已记录的公众号'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.acid
      delWxMp(id).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.getWxMpList(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    getWxMpList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      WxMpList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.getWxMpList(page)
    }
  },
  mounted () {
    this.getWxMpList(1)
  }
}
</script>
