<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="域名名称">
                <Input v-model="searchList.domainName"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 10px;" type="success" @click="handleCreate">创建域名</Button>
      </div>
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <domain-modal ref="DomainModal" @operate="ParentOperate"></domain-modal>
    <sql-domain-modal ref="SqlDomainModal" @sqlOperate="saveSql"></sql-domain-modal>
    <web-socket-msg ref="webSocketMsg" @wsClose="websocketClose"></web-socket-msg>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import DomainModal from './modal/domain-template'
import SqlDomainModal from './modal/sql-domain'
import webSocketMsg from '_c/modal/webSocket-msg'
import { getToken } from '@/libs/util'
import { createDomain, updateDomain, delDomain, DomainList, createSSL, createSQL, createCMS } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    DomainModal,
    SqlDomainModal,
    webSocketMsg
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        domainName: ''
      },
      columns: [
        { title: '域名ID', key: 'no' },
        {
          title: '域名',
          width: 150,
          key: 'domainName'
        },
        { title: '服务器ID', key: 'serverId' },
        { title: 'DNS类型',
          key: 'dnsType',
          render: (h, params) => {
            var data = params.row
            var type = data.dnsType
            var result = type ? this.$constant.getvalue('DnsType', type) : ''
            return h('span', result)
          }
        },
        { title: '是否安装HTTPS证书',
          key: 'sslFlag',
          render: (h, params) => {
            var data = params.row
            var sslFlag = data.sslFlag
            var result = this.$constant.getvalue('sslFlag', sslFlag)
            return h('span', result)
          }
        },
        { title: '是否提供管理后台',
          key: 'adminFlag',
          render: (h, params) => {
            var data = params.row
            var adminFlag = data.adminFlag
            var result = this.$constant.getvalue('adminFlag', adminFlag)
            return h('span', result)
          }
        },
        { title: '是否提供企业邮箱服务',
          key: 'mailboxService',
          render: (h, params) => {
            var data = params.row
            var mailboxService = data.mailboxService
            var result = this.$constant.getvalue('mailboxService', mailboxService)
            return h('span', result)
          }
        },
        { title: '是否提供HTTP服务',
          key: 'hostService',
          render: (h, params) => {
            var data = params.row
            var hostService = data.hostService
            var result = this.$constant.getvalue('hostService', hostService)
            return h('span', result)
          }
        },
        { title: 'HTTP服务类型',
          key: 'hostType',
          render: (h, params) => {
            var data = params.row
            var hostType = data.hostType
            var result = this.$constant.getvalue('hostType', hostType)
            return h('span', result)
          }
        },
        { title: '是否提供CMS服务',
          key: 'cmsFlag',
          render: (h, params) => {
            var data = params.row
            var cmsFlag = data.cmsFlag
            var result = this.$constant.getvalue('cmsFlag', cmsFlag)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          width: 250,
          render: (h, params) => {
            var data = params.row
            var sslFlag = (data.sslFlag === 1)
            var sqlService = (data.sqlService === 1)
            var cmsFlag = (data.cmsFlag === 1)
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
                  this.handleDel(data, 'del')
                }
              }
            }, '删除')
            var ssl = h('Button', {
              props: {
                type: 'warning',
                size: 'small',
                disabled: sslFlag
              },
              on: {
                click: () => {
                  this.CreateHandle(data, 'ssl')
                }
              }
            }, '安装SSL证书')
            var sql = h('Button', {
              props: {
                type: 'success',
                size: 'small',
                disabled: sqlService
              },
              on: {
                click: () => {
                  this.CreateHandle(data, 'sql')
                }
              }
            }, '安装SQL服务')
            var cms = h('Button', {
              props: {
                type: 'info',
                size: 'small',
                disabled: cmsFlag
              },
              on: {
                click: () => {
                  this.CreateHandle(data, 'cms')
                }
              }
            }, '安装CMS服务')
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [update, del, ssl, sql, cms])
            return result
          }
        }
      ],
      tableData: [],
      websock: null,
      timeout: 40000,
      timeoutObj: null,
      arrWs: [],
      arrTimeOut: 500,
      arrTimeOutObj: null
    }
  },
  methods: {
    // 创建服务
    CreateHandle (data, type) {
      if (type === 'ssl' || type === 'cms' || type === 'del') {
        this.$refs.ready.ready_title = '安装'
        this.$refs.ready.ready_judge = type
        this.$refs.ready.ready_modal = true
        this.$refs.ready.ready_content = '是否安装-' + type
        this.$refs.ready.ready_params = data
      } else if (type === 'sql') {
        this.$refs.SqlDomainModal.$refs.sql_form.resetFields()
        this.$refs.SqlDomainModal.sql_modal = true
        this.$refs.SqlDomainModal.sql_Id = data.no
      } else {}
    },
    saveSql () {
      var no = this.$refs.SqlDomainModal.sql_Id
      var sqlPasswd = this.$refs.SqlDomainModal.sql_form.sqlPasswd
      var sqlQuota = this.$refs.SqlDomainModal.sql_form.sqlQuota
      var data = {
        'no': no,
        'sqlPasswd': sqlPasswd,
        'sqlQuota': sqlQuota,
        'sqlService': 1
      }
      this.$refs.SqlDomainModal.$refs.sql_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          createSQL(data).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('成功创建')
              this.$refs.SqlDomainModal.sql_modal = false
              this.$refs.webSocketMsg.webSocketMsg_modal = true
              this.initWebSocket()
            } else {
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else {
          return false
        }
      })
    },
    // 删除域名
    handleDel (x) {
      var data = x
      var name = data.domainName
      this.$refs.ready.ready_title = '删除域名'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除域名-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      var type = this.$refs.ready.ready_judge
      var no = this.$refs.ready.ready_params.no
      this.$Spin.show()
      if (type) {
        if (type === 'ssl') {
          createSSL(no).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('成功创建')
              this.$refs.SqlDomainModal.ready_modal = false
              this.$refs.webSocketMsg.webSocketMsg_modal = true
              this.initWebSocket()
            } else {
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else if (type === 'cms') {
          createCMS(no).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('成功创建')
              this.$refs.SqlDomainModal.ready_modal = false
              this.$refs.webSocketMsg.webSocketMsg_modal = true
              this.initWebSocket()
            } else {
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else {}
      } else {
        delDomain(no).then(res => {
          this.$Spin.hide()
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功删除')
          this.searchTable(1)
        }).catch(ess => {
          this.$Spin.hide()
          this.$Message.error('请联系管理员')
        })
      }
    },
    // websocket
    initWebSocket () { // 初始化websocket
      // websocket地址
      const wsuri = 'wss://apia.jia.wydiy.com/websocket?access_token=' + getToken()
      this.websock = new WebSocket(wsuri)
      this.websock.onopen = this.websocketonopen
      this.websock.onmessage = this.websocketonmessage
      this.websock.onclose = this.websocketonclose
    },
    websocketonopen (e) { // 数据开始连接
      // console.log(e)
      this.heartCheckStart()
    },
    websocketonmessage (e) { // 数据接收
      // this.heartCheckReset()
      const redata = e.data
      this.arrWs.push(redata)
      if (redata === 'ssl_create_completed' || redata === 'sql_create_completed' || redata === 'cms_create_completed') {
        this.$refs.webSocketMsg.webSocketMsg_disabled = false
        this.$refs.webSocketMsg.webSocketMsg_status = '关闭'
      } else {}
    },
    websocketSend (agentData) { // 数据发送
      this.websock.send(agentData)
    },
    websocketonclose (e) { // 关闭
      console.log('connection closed (' + e.code + ')')
    },
    heartCheckStart () {
      var _this = this
      this.timeoutObj = setInterval(function () {
        _this.websocketSend('HeartBeat')
      }, this.timeout)
      this.arrTimeOutObj = setInterval(function () {
        var arr = _this.arrWs.splice(0, _this.arrWs.length)
        arr.forEach(function (val, i) {
          var parent = _this.$refs.webSocketMsg.$refs.web_wrap
          var html_redata = '<li>' + val + '</li>'
          _this.$constant.append(parent, html_redata)
        })
        var msgEnd = _this.$refs.webSocketMsg.$refs.msg_end
        msgEnd.scrollIntoView()
      }, this.arrTimeOut)
      this.$refs.webSocketMsg.webSocketMsg_disabled = true
      this.$refs.webSocketMsg.webSocketMsg_status = '安装中'
    },
    heartCheckReset () {
      clearTimeout(this.timeoutObj)
      this.heartCheckStart()
    },
    websocketClose () { // 主动关闭websocket
      this.websock.close()
      clearTimeout(this.timeoutObj)
      clearTimeout(this.arrTimeOutObj)
      this.$refs.webSocketMsg.webSocketMsg_modal = false
      this.searchTable(1)
    },
    // 创建域名
    handleCreate () {
      this.$refs.DomainModal.$refs.domain_form.resetFields()
      this.$refs.DomainModal.domain_title = '创建域名'
      this.$refs.DomainModal.domain_modal = true
      this.$refs.DomainModal.domain_form.domainName = ''
      this.$refs.DomainModal.domain_form.dnsType = 'aly'
      this.$refs.DomainModal.domain_form.dnsKey = ''
      this.$refs.DomainModal.domain_form.dnsToken = ''
      this.$refs.DomainModal.domain_form.serverId = 0
      this.$refs.DomainModal.domain_judge = 'create'
    },
    // 修改域名
    handleUpdate (x) {
      var data = x
      var domainName = data.domainName ? data.domainName : ''
      var dnsType = data.dnsType ? data.dnsType : ''
      var dnsKey = data.dnsKey ? data.dnsKey : ''
      var dnsToken = data.dnsToken ? data.dnsToken : ''
      var serverId = data.serverId ? data.serverId : ''
      var no = data.no
      this.$refs.DomainModal.$refs.domain_form.resetFields()
      this.$refs.DomainModal.domain_title = '修改域名'
      this.$refs.DomainModal.domain_modal = true
      this.$refs.DomainModal.domain_Id = no
      this.$refs.DomainModal.domain_form.domainName = domainName
      this.$refs.DomainModal.domain_form.dnsType = dnsType
      this.$refs.DomainModal.domain_form.dnsKey = dnsKey
      this.$refs.DomainModal.domain_form.dnsToken = dnsToken
      this.$refs.DomainModal.domain_form.serverId = serverId
      this.$refs.DomainModal.domain_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.DomainModal.domain_judge
      var domainName = this.$refs.DomainModal.domain_form.domainName
      var dnsType = this.$refs.DomainModal.domain_form.dnsType
      var dnsKey = this.$refs.DomainModal.domain_form.dnsKey
      var dnsToken = this.$refs.DomainModal.domain_form.dnsToken
      var serverId = this.$refs.DomainModal.domain_form.serverId
      var no = this.$refs.DomainModal.domain_Id
      var data = {
        'domainName': domainName,
        'dnsType': dnsType,
        'dnsKey': dnsKey,
        'dnsToken': dnsToken,
        'serverId': serverId
      }
      if (judge === 'create') {
        this.$refs.DomainModal.$refs.domain_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createDomain(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.DomainModal.domain_modal = false
                this.searchTable(1)
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
        this.$refs.DomainModal.$refs.domain_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['no'] = no
            updateDomain(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.DomainModal.domain_modal = false
                this.searchTable(1)
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
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getDomainList(page, obj)
    },
    // 获取域名
    getDomainList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      DomainList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    }
  },
  mounted () {
    this.searchTable(1)
  }
}
</script>

<style scoped>

</style>
