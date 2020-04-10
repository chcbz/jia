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
        <Button style="margin: 10px 10px;" type="success" @click="handleCreate">新增用户组</Button>
      </div>
      <tables ref="tables"  v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <group-modal ref="GroupModal" @operate="ParentOperate"></group-modal>
    <role-transfer ref="RoleTransfer" @RoleChange="ChangeRole"></role-transfer>
    <user-transfer ref="UserTransfer" @AddObj="ObjAdd" @RemoveObj="ObjRemove"></user-transfer>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import GroupModal from './modal/group-template'
import RoleTransfer from './modal/role-transfer'
import UserTransfer from './modal/user-transfer'
import { createGroup, updateGroup, getGroupList, delGroup, changeRoleGroup, getRoleGroup, getUserGroup, addUserGroup, delUserGroup } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    GroupModal,
    RoleTransfer,
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
        { title: '用户组名', key: 'name' },
        { title: '编码', key: 'code' },
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
            var role = h('Button', {
              props: {
                type: 'primary',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleRole(data)
                }
              }
            }, '角色')
            var user = h('Button', {
              props: {
                type: 'success',
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
            }, [update, del, role, user])
            return result
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 初始化
    initVue () {
      this.$refs.RoleTransfer.getRole(2)
      this.searchTable(1)
    },
    // 添加用户
    ObjAdd (arr) {
      var id = this.$refs.UserTransfer.UserTransfer_templateId
      var userIds = [arr['0'].key]
      var data = {
        'id': id,
        'userIds': userIds
      }
      addUserGroup(data).then(res => {
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
      delUserGroup(data).then(res => {
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
      getUserGroup(uData).then(res => {
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
    // 更换角色
    ChangeRole () {
      var id = this.$refs.RoleTransfer.RoleTransfer_templateId
      var roleArr = this.$refs.RoleTransfer.RoleKeys
      var initRoleArr = this.$refs.RoleTransfer.RoleInitData
      var roleIds = []
      roleArr.forEach((val, i) => {
        var vKey = parseInt(val)
        initRoleArr.forEach((value, t) => {
          var dKey = value.key
          var dId = parseInt(value.id)
          if (vKey === dKey) {
            roleIds.push(dId)
          } else {}
        })
      })
      var data = {
        'id': id,
        'roleIds': roleIds
      }
      changeRoleGroup(data).then(res => {
        if (res.data.msg === 'ok') {
          this.$Message.success('成功保存')
          this.searchTable(1)
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 打开角色弹出
    handleRole (x) {
      var data = x
      var templateId = data.id
      this.$refs.RoleTransfer.RoleTransfer_title = '角色管理'
      this.$refs.RoleTransfer.RoleTransfer_templateId = templateId
      var rData = {
        'pageNum': 1,
        'search': '{"id":' + templateId + '}'
      }
      getRoleGroup(rData).then(res => {
        var roleIds = []
        var length = res.data.total
        var r_data = res.data.data
        var initRoleArr = this.$refs.RoleTransfer.RoleInitData
        if (length > 0) {
          r_data.forEach((val, i) => {
            var r_id = val.id
            initRoleArr.forEach((value, t) => {
              var init_id = value.id
              var init_key = value.key
              if (r_id === init_id) {
                roleIds.push(init_key)
              } else {}
            })
          })
        } else {}
        this.$refs.RoleTransfer.RoleKeys = roleIds
        this.$refs.RoleTransfer.RoleTransfer_modal = true
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 创建用户组
    handleCreate () {
      this.$refs.GroupModal.$refs.GroupTemplate_form.resetFields()
      this.$refs.GroupModal.GroupTemplate_title = '创建用户组'
      this.$refs.GroupModal.GroupTemplate_modal = true
      this.$refs.GroupModal.GroupTemplate_templateId = ''
      this.$refs.GroupModal.GroupTemplate_form.name = ''
      this.$refs.GroupModal.GroupTemplate_form.code = ''
      this.$refs.GroupModal.GroupTemplate_form.remark = ''
      this.$refs.GroupModal.GroupTemplate_judge = 'create'
    },
    // 修改用户组
    handleUpdate (x) {
      var data = x
      var templateId = data.id
      var name = data.name
      var code = data.code
      var remark = data.remark
      this.$refs.GroupModal.$refs.GroupTemplate_form.resetFields()
      this.$refs.GroupModal.GroupTemplate_title = '修改用户组'
      this.$refs.GroupModal.GroupTemplate_modal = true
      this.$refs.GroupModal.GroupTemplate_form.name = name
      this.$refs.GroupModal.GroupTemplate_form.code = code
      this.$refs.GroupModal.GroupTemplate_form.remark = remark
      this.$refs.GroupModal.GroupTemplate_templateId = templateId
      this.$refs.GroupModal.GroupTemplate_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.GroupModal.GroupTemplate_judge
      var name = this.$refs.GroupModal.GroupTemplate_form.name
      var code = this.$refs.GroupModal.GroupTemplate_form.code
      var remark = this.$refs.GroupModal.GroupTemplate_form.remark
      var templateId = this.$refs.GroupModal.GroupTemplate_templateId
      var data = {
        'name': name,
        'code': code,
        'remark': remark
      }
      if (judge === 'create') {
        this.$refs.GroupModal.$refs.GroupTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createGroup(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.GroupModal.GroupTemplate_modal = false
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
        this.$refs.GroupModal.$refs.GroupTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = templateId
            updateGroup(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.GroupModal.GroupTemplate_modal = false
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
    // 删除用户组
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除用户组'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除用户组-' + name
      this.$refs.ready.ready_params = data
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.GroupTable(page, JSON.stringify(obj))
    },
    GroupTable (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      getGroupList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除用户组
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delGroup(id).then(res => {
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
  }
}
</script>
