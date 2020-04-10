<template>
  <div>
    <Card>
      <Button style="margin: 10px 0;" type="success" @click="handleCreate">创建Samba虚拟目录</Button>
      <tables ref="tables" editable  border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <smb-vdir-modal ref="smbVdirModal" :serverId="parseInt(serverId)" @smbVdirNext="ParentOperate"></smb-vdir-modal>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import smbVdirModal from '../modal/smbVdir-template'
import { SmbVdirList, createSmbVdir, updateSmbVdir, delSmbVdir } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    smbVdirModal
  },
  data () {
    return {
      serverId: '',
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '用户',
          key: 'user',
          render: (h, params) => {
            var data = params.row
            var user = data.user
            var name = data.name
            var value = user + '/' + name
            return h('span', value)
          }
        },
        { title: '虚拟目录名',
          key: 'name'
        },
        { title: '物理路径',
          key: 'path'
        },
        { title: '是否有效',
          key: 'available'
        },
        { title: '是否可写',
          key: 'writable'
        },
        { title: '是否可浏览',
          key: 'browseable'
        },
        { title: '是否可打印',
          key: 'printable'
        },
        { title: '描述',
          key: 'comment'
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
    // 创建Samba虚拟目录
    handleCreate () {
      this.$refs.smbVdirModal.$refs.smbVdir_form.resetFields()
      this.$refs.smbVdirModal.smbVdir_title = '创建Samba虚拟目录'
      this.$refs.smbVdirModal.smbVdir_modal = true
      this.$refs.smbVdirModal.smbVdir_Id = ''
      this.$refs.smbVdirModal.smbVdir_form.name = ''
      this.$refs.smbVdirModal.smbVdir_form.user = ''
      this.$refs.smbVdirModal.smbVdir_form.path = ''
      this.$refs.smbVdirModal.smbVdir_form.available = 'yes'
      this.$refs.smbVdirModal.smbVdir_form.writable = 'yes'
      this.$refs.smbVdirModal.smbVdir_form.browseable = 'yes'
      this.$refs.smbVdirModal.smbVdir_form.printable = 'no'
      this.$refs.smbVdirModal.smbVdir_form.comment = ''
      this.$refs.smbVdirModal.smbVdir_judge = 'create'
    },
    // 修改Samba虚拟目录
    handleUpdate (x) {
      this.$refs.smbVdirModal.$refs.smbVdir_form.resetFields()
      this.$refs.smbVdirModal.smbVdir_title = '修改Samba虚拟目录'
      this.$refs.smbVdirModal.smbVdir_modal = true
      this.$refs.smbVdirModal.smbVdir_Id = x.id
      this.$refs.smbVdirModal.smbVdir_form.name = x.name
      this.$refs.smbVdirModal.smbVdir_form.user = x.user
      this.$refs.smbVdirModal.smbVdir_form.path = x.path
      this.$refs.smbVdirModal.smbVdir_form.available = x.available
      this.$refs.smbVdirModal.smbVdir_form.writable = x.writable
      this.$refs.smbVdirModal.smbVdir_form.browseable = x.browseable
      this.$refs.smbVdirModal.smbVdir_form.printable = x.printable
      this.$refs.smbVdirModal.smbVdir_form.comment = x.comment
      this.$refs.smbVdirModal.smbVdir_judge = 'update'
    },
    // 创建、修改
    ParentOperate () {
      const serverId = this.serverId
      var judge = this.$refs.smbVdirModal.smbVdir_judge
      var id = this.$refs.smbVdirModal.smbVdir_Id
      var name = this.$refs.smbVdirModal.smbVdir_form.name
      var user = this.$refs.smbVdirModal.smbVdir_form.user
      var path = this.$refs.smbVdirModal.smbVdir_form.path
      var available = this.$refs.smbVdirModal.smbVdir_form.available
      var writable = this.$refs.smbVdirModal.smbVdir_form.writable
      var browseable = this.$refs.smbVdirModal.smbVdir_form.browseable
      var printable = this.$refs.smbVdirModal.smbVdir_form.printable
      var comment = this.$refs.smbVdirModal.smbVdir_form.comment
      var data = {
        'name': name,
        'user': user,
        'path': path,
        'available': available,
        'writable': writable,
        'browseable': browseable,
        'printable': printable,
        'comment': comment
      }
      if (judge === 'create') {
        this.$refs.smbVdirModal.$refs.smbVdir_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['serverId'] = serverId
            createSmbVdir(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.smbVdirModal.smbVdir_modal = false
                this.getSmbVdirList(1)
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
        this.$refs.smbVdirModal.$refs.smbVdir_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            updateSmbVdir(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.smbVdirModal.smbVdir_modal = false
                this.getSmbVdirList(1)
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
    // 删除Samba虚拟目录
    handleDelete (x) {
      var data = x
      var name = data.comment
      this.$refs.ready.ready_title = '删除Samba虚拟目录'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delSmbVdir(id).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.getSmbVdirList(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    getSmbVdirList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      const serverId = this.serverId
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': {
          'serverId': serverId
        }
      }
      SmbVdirList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.getSmbVdirList(page)
    },
    // 获取ServerId
    getServerId () {
      var w_url = window.location.href // 获取当前页面url
      var arr = w_url.split('/')
      var serverId = arr[arr.length - 1] // 地址栏链接页面
      this.serverId = parseInt(serverId)
    }
  },
  mounted () {
    this.getSmbVdirList(1)
  },
  created () {
    this.getServerId()
  },
  watch: {
    '$route' (to, from) {
      this.getServerId()
      this.getSmbVdirList(1)
    }
  }
}
</script>
