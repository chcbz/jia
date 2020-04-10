<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="服务器名称">
                <Input v-model="searchList.serverName"></Input>
              </FormItem>
              <FormItem label="状态">
                <Select v-model="searchList.status" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in ServerStatus" :value="option.id" :key="v">{{option.str}}</Option>
                </Select>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 10px;" type="success" @click="handleCreate">创建服务器</Button>
      </div>
      <!--<Button style="margin: 10px 0;" type="error" @click="test">测试</Button>-->
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <Server-modal ref="ServerModal" @operate="ParentOperate"></Server-modal>
    <web-socket-msg ref="webSocketMsg" @wsClose="websocketClose"></web-socket-msg>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import ServerModal from './modal/server-template'
import webSocketMsg from '_c/modal/webSocket-msg'
import { getToken } from '@/libs/util'
import { createServer, updateServer, delServer, ServerList } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    ServerModal,
    webSocketMsg
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        status: '',
        serverName: ''
      },
      ServerStatus: [],
      columns: [
        {
          title: '服务器名',
          key: 'serverName',
          render: (h, params) => {
            var data = params.row
            var serverName = data.serverName
            return h(
              'span', {
                style: {
                  color: '#00b5ff',
                  cursor: 'pointer'
                },
                on: {
                  click: () => {
                    this.havego('server_detailed/', data)
                  }
                }
              }, serverName)
          }
        },
        { title: '服务器描述', key: 'serverDescription' },
        { title: 'SSH端口', key: 'sshPort' },
        { title: 'IP地址', key: 'ip' },
        { title: '状态',
          key: 'status',
          render: (h, params) => {
            var data = params.row
            var status = data.status
            var result = this.$constant.getvalue('ServerStatus', status)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var clientId = data.clientId
            var update = h('Button', {
              props: {
                type: 'info',
                size: 'small',
                disabled: !clientId
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
                size: 'small',
                disabled: !clientId
              },
              on: {
                click: () => {
                  this.handleDel(data)
                }
              }
            }, '删除')
            var have = h('Button', {
              props: {
                type: 'success',
                size: 'small'
              },
              on: {
                click: () => {
                  this.havego('server_app_list/', data)
                }
              }
            }, '服务')
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [update, del, have])
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
    // test () {
    //   this.$refs.webSocketMsg.webSocketMsg_modal = true
    //   this.initWebSocket()
    // },
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
      // var arr = this.$refs.webSocketMsg.webSocketMsg_arr
      // arr.push(redata)

      // var parent = this.$refs.webSocketMsg.$refs.web_wrap
      // var html_redata = '<li>' + redata + '</li>'
      // this.$constant.append(parent, html_redata)
      // var msgEnd = this.$refs.webSocketMsg.$refs.msg_end
      // msgEnd.scrollIntoView()

      // parent.scrollTop = parent.scrollHeight
      // console.log(redata)
      if (redata === 'server_create_completed') {
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
    // 跳转到已安装列表
    havego (path, data) {
      var id = data.id
      var serverName = data.serverName
      if (path === 'server_detailed/') {
        this.$router.push({ path: path + id })
      } else {
        this.$router.push({ path: path + id, query: { serverId: id, serverName: serverName } })
      }
    },
    // 创建服务器
    handleCreate () {
      this.$refs.ServerModal.$refs.Server_form.resetFields()
      this.$refs.ServerModal.Server_title = '创建服务器'
      this.$refs.ServerModal.Server_modal = true
      this.$refs.ServerModal.Server_Id = ''
      this.$refs.ServerModal.Server_form.serverName = ''
      this.$refs.ServerModal.Server_form.serverDescription = ''
      this.$refs.ServerModal.Server_form.ip = ''
      this.$refs.ServerModal.Server_form.sshPort = ''
      this.$refs.ServerModal.Server_form.sshUser = ''
      this.$refs.ServerModal.Server_form.sshPassword = ''
      this.$refs.ServerModal.Server_form.consolePort = ''
      this.$refs.ServerModal.Server_form.consoleToken = ''
      this.$refs.ServerModal.Server_judge = 'create'
    },
    // 修改服务器
    handleUpdate (x) {
      var data = x
      var ServerId = data.id
      var serverName = data.serverName
      var serverDescription = data.serverDescription
      var ip = data.ip
      var sshPort = data.sshPort
      var sshUser = data.sshUser
      var sshPassword = data.sshPassword
      var consolePort = data.consolePort
      var consoleToken = data.consoleToken
      this.$refs.ServerModal.$refs.Server_form.resetFields()
      this.$refs.ServerModal.Server_title = '修改服务器'
      this.$refs.ServerModal.Server_modal = true
      this.$refs.ServerModal.Server_Id = ServerId
      this.$refs.ServerModal.Server_form.serverName = serverName
      this.$refs.ServerModal.Server_form.serverDescription = serverDescription
      this.$refs.ServerModal.Server_form.ip = ip
      this.$refs.ServerModal.Server_form.sshPort = sshPort
      this.$refs.ServerModal.Server_form.sshUser = sshUser
      this.$refs.ServerModal.Server_form.sshPassword = sshPassword
      this.$refs.ServerModal.Server_form.consolePort = consolePort
      this.$refs.ServerModal.Server_form.consoleToken = consoleToken
      this.$refs.ServerModal.Server_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.ServerModal.Server_judge
      var ServerId = this.$refs.ServerModal.Server_Id
      var serverName = this.$refs.ServerModal.Server_form.serverName
      var serverDescription = this.$refs.ServerModal.Server_form.serverDescription
      var ip = this.$refs.ServerModal.Server_form.ip
      var sshPort = this.$refs.ServerModal.Server_form.sshPort
      var sshUser = this.$refs.ServerModal.Server_form.sshUser
      var sshPassword = this.$refs.ServerModal.Server_form.sshPassword
      var consolePort = this.$refs.ServerModal.Server_form.consolePort
      var consoleToken = this.$refs.ServerModal.Server_form.consoleToken
      var data = {
        'serverName': serverName,
        'serverDescription': serverDescription,
        'ip': ip,
        'sshPort': sshPort,
        'sshUser': sshUser,
        'sshPassword': sshPassword,
        'consolePort': consolePort,
        'consoleToken': consoleToken
      }
      if (judge === 'create') {
        this.$refs.ServerModal.$refs.Server_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createServer(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.ServerModal.Server_modal = false
                this.$refs.webSocketMsg.webSocketMsg_modal = true
                this.initWebSocket()
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
        this.$refs.ServerModal.$refs.Server_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = ServerId
            updateServer(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.ServerModal.Server_modal = false
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
      this.getServerList(page, obj)
    },
    // 初始化
    initView () {
      this.ServerStatus = this.$constant.ServerStatus
      // var arr = [1, 2, 3]
      // var bArr = arr.splice(0, arr.length)
      // var bArr = arr.push(5)
      // console.log(arr)
      // console.log(bArr)
      // var a = 2
      // var b = a - 2
      // console.log(a)
      // console.log(b)
    },
    // 获取服务器
    getServerList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      ServerList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除服务器
    handleDel (x) {
      var data = x
      var name = data.serverName
      this.$refs.ready.ready_title = '删除服务器'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除服务器-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      var ServerId = this.$refs.ready.ready_params.id
      this.$Spin.show()
      delServer(ServerId).then(res => {
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
  mounted () {
    this.initView()
    this.searchTable(1)
  }
}
</script>

<style scoped>

</style>
