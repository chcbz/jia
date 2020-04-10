<template>
  <div>
    <Card>
      <tables ref="tables"   border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ldap-modal ref="ldapModal" @ldapNext="goLdap"></ldap-modal>
    <samba-modal ref="sambaModal" @sambaNext="goSamba"></samba-modal>
    <ready ref="ready" @Next="ParentNext"></ready>
    <web-socket-msg ref="webSocketMsg" @wsClose="websocketClose"></web-socket-msg>
  </div>
</template>
<script>
import Tables from '_c/tables'
import ldapModal from '../modal/ldap-template'
import sambaModal from '../modal/samba-template'
import Ready from '_c/modal/ready'
import webSocketMsg from '_c/modal/webSocket-msg'
import { getToken } from '@/libs/util'
import { haveServerList, installServer, LdapInstall, SambaInstall, stopServer, restartServer, startServer } from '@/api/data'

export default {
  components: {
    Tables,
    ldapModal,
    sambaModal,
    Ready,
    webSocketMsg
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '服务名称', key: 'name' },
        { title: '状态',
          key: 'status',
          render: (h, params) => {
            var data = params.row
            var status = data.status
            var result = this.$constant.getvalue('ServerAppStatus', parseInt(status))
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          // fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var status = parseInt(data.status)
            var install = h('Button', {
              props: {
                type: 'info',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleOperate(data, 'install')
                }
              }
            }, '安装')
            var start = h('Button', {
              props: {
                type: 'success',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleOperate(data, 'start')
                }
              }
            }, '启动')
            var stop = h('Button', {
              props: {
                type: 'error',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleOperate(data, 'stop')
                }
              }
            }, '停止')
            var restart = h('Button', {
              props: {
                type: 'success',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleOperate(data, 'restart')
                }
              }
            }, '重启')
            var btn
            if (status < 0) {
              btn = [install]
            } else if (status > 0) {
              btn = [stop, restart]
            } else {
              btn = [start]
            }
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, btn)
            return result
          }
        }
      ],
      tableData: [],
      // resData: [],
      operateArr: [{
        id: 'install',
        name: '安装',
        status: -1
      }, {
        id: 'start',
        name: '启动',
        status: 0
      }, {
        id: 'stop',
        name: '停止',
        status: 1
      }, {
        id: 'restart',
        name: '重启',
        status: 1
      }],
      websock: null,
      timeout: 40000,
      timeoutObj: null,
      arrWs: [],
      arrTimeOut: 500,
      arrTimeOutObj: null
      // timeout: 5000,
      // timeoutObj: null
    }
  },
  methods: {
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
      if (redata === 'exec_command_completed') {
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
      this.haveServer()
    },
    // 操作服务
    handleOperate (data, type) {
      var name = data.name
      var typeName
      if (name === 'openldap' && type === 'install') {
        this.NextLdap(data, type)
      } else if (name === 'samba' && type === 'install') {
        this.NextSamba(data, type)
      } else {
        this.operateArr.forEach((item, index) => {
          if (item.id === type) {
            typeName = item.name
          } else {}
        })
        this.$refs.ready.ready_title = typeName + '服务'
        this.$refs.ready.ready_modal = true
        this.$refs.ready.ready_content = '是否' + typeName + '-' + name
        this.$refs.ready.ready_judge = type
        this.$refs.ready.ready_params = data
      }
    },
    // LDAP专用
    NextLdap (data, type) {
      this.$refs.ldapModal.LDAP_modal = true
      this.$refs.ldapModal.LDAP_params = data
      this.$refs.ldapModal.LDAP_form.base = ''
      this.$refs.ldapModal.LDAP_form.user = ''
      this.$refs.ldapModal.LDAP_form.password = ''
      this.$refs.ldapModal.LDAP_form.port = ''
    },
    goLdap () {
      this.$refs.ldapModal.$refs.LDAP_form.validate((valid) => {
        if (valid) {
          var name = this.$refs.ldapModal.LDAP_params.name
          var serverId = this.$route.query.serverId
          var base = this.$refs.ldapModal.LDAP_form.base
          var user = this.$refs.ldapModal.LDAP_form.user
          var password = this.$refs.ldapModal.LDAP_form.password
          var port = this.$refs.ldapModal.LDAP_form.port
          var data = {
            'name': name,
            'serverId': parseInt(serverId),
            'params': {
              'base': base,
              'user': user,
              'password': password,
              'port': parseInt(port)
            }
          }
          this.ParentNext(data)
          this.$refs.ldapModal.LDAP_modal = false
        } else {
          return false
        }
      })
    },
    // Samba专用
    NextSamba (data, type) {
      this.$refs.sambaModal.samba_modal = true
      this.$refs.sambaModal.samba_params = data
      this.$refs.sambaModal.samba_form.base = ''
      this.$refs.sambaModal.samba_form.user = ''
      this.$refs.sambaModal.samba_form.password = ''
      this.$refs.sambaModal.samba_form.url = ''
    },
    goSamba () {
      this.$refs.sambaModal.$refs.samba_form.validate((valid) => {
        if (valid) {
          var name = this.$refs.sambaModal.samba_params.name
          var serverId = this.$route.query.serverId
          var base = this.$refs.sambaModal.samba_form.base
          var user = this.$refs.sambaModal.samba_form.user
          var password = this.$refs.sambaModal.samba_form.password
          var url = this.$refs.sambaModal.samba_form.url
          var data = {
            'name': name,
            'serverId': parseInt(serverId),
            'params': {
              'base': base,
              'user': user,
              'password': password,
              'url': url
            }
          }
          this.ParentNext(data)
          this.$refs.sambaModal.samba_modal = false
        } else {
          return false
        }
      })
    },
    ParentNext (obj) {
      var name = this.$refs.ready.ready_params.name
      const serverId = this.$route.query.serverId
      var judge
      if (obj) {
        judge = 'install'
      } else {
        judge = this.$refs.ready.ready_judge
      }
      this.$Spin.show()
      if (judge === 'install') {
        var data
        if (obj.name === 'openldap') {
          data = obj
          LdapInstall(data).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('开始安装')
              this.$refs.webSocketMsg.webSocketMsg_modal = true
              this.initWebSocket()
              // this.checkServer()
            } else {
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else if (obj.name === 'samba') {
          data = obj
          SambaInstall(data).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('开始安装')
              this.$refs.webSocketMsg.webSocketMsg_modal = true
              this.initWebSocket()
              // this.checkServer()
            } else {
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else {
          data = {
            'serverId': serverId,
            'name': name
          }
          installServer(data).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('开始安装')
              this.$refs.webSocketMsg.webSocketMsg_modal = true
              this.initWebSocket()
              // this.checkServer()
            } else {
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        }
      } else if (judge === 'start') {
        startServer(serverId, name).then(res => {
          this.$Spin.hide()
          if (res.data.msg === 'ok') {
            this.$Message.success('开始启动')
            this.$refs.ready.ready_modal = false
            this.haveServer()
          } else {
            this.$Message.error(res.data.msg)
          }
        }).catch(ess => {
          this.$Spin.hide()
          this.$Message.error('请联系管理员')
        })
      } else if (judge === 'stop') {
        stopServer(serverId, name).then(res => {
          this.$Spin.hide()
          if (res.data.msg === 'ok') {
            this.$Message.success('开始停止')
            this.$refs.ready.ready_modal = false
            this.haveServer()
          } else {
            this.$Message.error(res.data.msg)
          }
        }).catch(ess => {
          this.$Spin.hide()
          this.$Message.error('请联系管理员')
        })
      } else {
        restartServer(serverId, name).then(res => {
          this.$Spin.hide()
          if (res.data.msg === 'ok') {
            this.$Message.success('开始重启')
            this.$refs.ready.ready_modal = false
            this.haveServer()
          } else {
            this.$Message.error(res.data.msg)
          }
        }).catch(ess => {
          this.$Spin.hide()
          this.$Message.error('请联系管理员')
        })
      }
    },
    // 检查服务状态
    // checkServer () {
    //   var name = this.$refs.ready.ready_params.name
    //   const serverId = this.$route.query.serverId
    //   statusServer(serverId, name).then(res => {
    //     if (res.data.code === 'E999') {
    //       this.$Message.success('已完成安装')
    //       this.$refs.ready.ready_modal = false
    //       this.haveServer()
    //       this.$Spin.hide()
    //       clearTimeout(this.timeoutObj)
    //     } else {
    //       // this.$Message.error(res.data.msg)
    //       var _this = this
    //       this.timeoutObj = setTimeout(function () {
    //         _this.checkServer()
    //       }, this.timeout)
    //     }
    //   }).catch(ess => {
    //     this.$Spin.hide()
    //     this.$Message.error('请联系管理员')
    //   })
    // },
    // 已安装服务
    haveServer () {
      const serverId = this.$route.query.serverId
      var data = {
        'serverId': serverId
      }
      haveServerList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.data.length
        this.$refs.tables.insidePageSize = 20
      })
    },
    // 分页
    ParentChange (page) {
      this.haveServer()
    }
  },
  mounted () {
    this.haveServer()
  },
  created () {

  },
  watch: {
    '$route' (to, from) {
      if (this.$route.query.serverId) {
        this.haveServer()
      }
    }
  }
}
</script>
