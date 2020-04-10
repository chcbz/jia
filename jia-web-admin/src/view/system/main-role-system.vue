<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="角色名">
                <Input v-model="searchList.name"></Input>
              </FormItem>
              <FormItem label="编码">
                <Input v-model="searchList.code"></Input>
              </FormItem>
              <FormItem label="备注">
                <Input v-model="searchList.remark"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 10px;" type="success" @click="handleCreate">创建角色</Button>
      </div>
      <tables ref="tables"  border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
      <!--<Button style="margin: 10px 0;" type="primary" @click="exportExcel">导出为Csv文件</Button>-->
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <role-modal ref="RoleModal" @operate="ParentOperate"></role-modal>
    <perms-transfer ref="PermsTransfer" @PermsChange="ChangePerms"></perms-transfer>
    <user-transfer ref="UserTransfer" @AddObj="ObjAdd" @RemoveObj="ObjRemove"></user-transfer>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import RoleModal from './modal/role-template'
import PermsTransfer from './modal/perms-transfer'
import UserTransfer from './modal/user-transfer'
import { createRole, updateRole, getRoleList, delRole, getPermsRole, changePermsRole, getRoleUser, addUserRole, delUserRole } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    RoleModal,
    PermsTransfer,
    UserTransfer
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        remark: '',
        code: '',
        name: ''
      },
      columns: [
        { title: '名称',
          key: 'name'
        },
        { title: '编码', key: 'code' },
        {
          title: '状态',
          key: 'status',
          render: (h, params) => {
            var data = params.row
            var status = data.status
            var result = this.$constant.getvalue('RoleStatus', status)
            return h('span', result)
          }
        },
        { title: '备注', key: 'remark' },
        {
          title: '操作',
          key: 'operate',
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
            var prems = h('Button', {
              props: {
                type: 'primary',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handlePerms(data)
                }
              }
            }, '权限')
            var user = h('Button', {
              props: {
                type: 'warning',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleUser(data)
                }
              }
            }, '用户')
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [update, del, prems, user])
            return result
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 添加用户
    ObjAdd (arr) {
      var id = this.$refs.UserTransfer.UserTransfer_templateId
      var userIds = [arr['0'].key]
      var data = {
        'id': id,
        'userIds': userIds
      }
      addUserRole(data).then(res => {
        if (res.data.msg === 'ok') {
          this.$Message.success('成功绑定')
          this.$refs.UserTransfer.UserHaveData.push(arr['0'])
          this.searchTable(1)
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 去除用户
    ObjRemove (key) {
      var id = this.$refs.UserTransfer.UserTransfer_templateId
      var userIds = [key]
      var data = {
        'id': id,
        'userIds': userIds
      }
      delUserRole(data).then(res => {
        if (res.data.msg === 'ok') {
          this.$Message.success('成功解绑')
          var UserHaveData = this.$refs.UserTransfer.UserHaveData
          UserHaveData.forEach((val, i) => {
            var uKey = val.key
            if (key === uKey) {
              UserHaveData.splice(i, 1)
            } else {}
          })
          this.searchTable(1)
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 打开用户弹出
    handleUser (x) {
      var data = x
      var templateId = data.id
      this.$refs.UserTransfer.UserTransfer_title = '用户管理'
      this.$refs.UserTransfer.UserTransfer_templateId = templateId
      this.$refs.UserTransfer.UserInitData = []
      this.$refs.UserTransfer.getUser(1)
      this.$refs.UserTransfer.searchUserInitData = ''
      this.$refs.UserTransfer.NewUserInitData = this.$refs.UserTransfer.UserInitData
      this.$refs.UserTransfer.searchUserHaveData = ''
      var uData = {
        'pageNum': 1,
        'search': '{"id":' + templateId + '}'
      }
      getRoleUser(uData).then(res => {
        var userIds = []
        var length = res.data.total
        var r_data = res.data.data
        if (length > 0) {
          r_data.forEach((val, i) => {
            var id = val.id
            var label = val.nickname
            var obj = { 'key': id, 'label': label }
            userIds.push(obj)
          })
        } else {}
        this.$refs.UserTransfer.UserHaveData = userIds
        this.$refs.UserTransfer.UserTransfer_modal = true
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 初始化
    initVue () {
      this.$refs.PermsTransfer.getPerms(1)
      this.searchTable(1)
    },
    // 更换权限
    ChangePerms () {
      var id = this.$refs.PermsTransfer.PermsTransfer_templateId
      var PermsArr = this.$refs.PermsTransfer.PermsKeys
      var initPermsArr = this.$refs.PermsTransfer.PermsInitData
      var permsIds = []
      PermsArr.forEach((val, i) => {
        var vKey = parseInt(val)
        initPermsArr.forEach((value, t) => {
          var dKey = value.key
          var dId = parseInt(value.id)
          if (vKey === dKey) {
            permsIds.push(dId)
          } else {}
        })
      })
      var data = {
        'id': id,
        'permsIds': permsIds
      }
      changePermsRole(data).then(res => {
        if (res.data.msg === 'ok') {
          this.$Message.success('成功保存')
          this.searchTable(1)
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 打开权限弹出
    handlePerms (x) {
      var data = x
      var templateId = data.id
      this.$refs.PermsTransfer.PermsTransfer_title = '权限管理'
      this.$refs.PermsTransfer.PermsTransfer_templateId = templateId
      var uData = {
        'pageNum': 1,
        'search': '{"id":' + templateId + '}'
      }
      getPermsRole(uData).then(res => {
        var permsIds = []
        var length = res.data.total
        var r_data = res.data.data
        var initPermsArr = this.$refs.PermsTransfer.PermsInitData
        if (length > 0) {
          r_data.forEach((val, i) => {
            var r_id = val.id
            initPermsArr.forEach((value, t) => {
              var init_id = value.id
              var init_key = value.key
              if (r_id === init_id) {
                permsIds.push(init_key)
              } else {}
            })
          })
        } else {}
        this.$refs.PermsTransfer.PermsKeys = permsIds
        this.$refs.PermsTransfer.PermsTransfer_modal = true
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 创建角色
    handleCreate () {
      this.$refs.RoleModal.$refs.RoleTemplate_form.resetFields()
      this.$refs.RoleModal.RoleTemplate_title = '创建角色'
      this.$refs.RoleModal.RoleTemplate_modal = true
      this.$refs.RoleModal.RoleTemplate_templateId = ''
      this.$refs.RoleModal.RoleTemplate_form.name = ''
      this.$refs.RoleModal.RoleTemplate_form.code = ''
      this.$refs.RoleModal.RoleTemplate_form.remark = ''
      this.$refs.RoleModal.RoleTemplate_judge = 'create'
    },
    // 修改角色
    handleUpdate (x) {
      var data = x
      var templateId = data.id
      var name = data.name
      var code = data.code
      var remark = data.remark
      this.$refs.RoleModal.$refs.RoleTemplate_form.resetFields()
      this.$refs.RoleModal.RoleTemplate_title = '修改角色'
      this.$refs.RoleModal.RoleTemplate_modal = true
      this.$refs.RoleModal.RoleTemplate_templateId = templateId
      this.$refs.RoleModal.RoleTemplate_form.name = name
      this.$refs.RoleModal.RoleTemplate_form.code = code
      this.$refs.RoleModal.RoleTemplate_form.remark = remark
      this.$refs.RoleModal.RoleTemplate_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.RoleModal.RoleTemplate_judge
      var name = this.$refs.RoleModal.RoleTemplate_form.name
      var code = this.$refs.RoleModal.RoleTemplate_form.code
      var remark = this.$refs.RoleModal.RoleTemplate_form.remark
      var templateId = this.$refs.RoleModal.RoleTemplate_templateId
      var data = {
        'name': name,
        'code': code,
        'level': 1,
        'remark': remark
      }
      if (judge === 'create') {
        this.$refs.RoleModal.$refs.RoleTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createRole(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.RoleModal.RoleTemplate_modal = false
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
        this.$refs.RoleModal.$refs.RoleTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = templateId
            updateRole(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.RoleModal.RoleTemplate_modal = false
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
    // 删除角色
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除角色'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除' + name
      this.$refs.ready.ready_params = data
    },
    // exportExcel () {
    //   this.$refs.tables.exportCsv({
    //     filename: `table-${(new Date()).valueOf()}.csv`
    //   })
    // },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      obj['level'] = 1
      this.getRole(page, JSON.stringify(obj))
    },
    // 获取角色列表
    getRole (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      getRoleList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除角色
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delRole(id).then(res => {
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
    this.initVue()
  },
  created () {}
}
</script>
