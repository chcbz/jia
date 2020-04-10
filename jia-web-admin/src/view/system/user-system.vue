<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="姓名">
                <Input v-model="searchList.nickname"></Input>
              </FormItem>
              <FormItem label="手机号码">
                <Input v-model="searchList.phone"></Input>
              </FormItem>
              <FormItem label="邮箱">
                <Input v-model="searchList.email"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 5px;" type="success" @click="handleCreate">新增用户</Button>
        <Button style="margin: 10px 5px;" type="primary" @click="handleUserOrgBind" >绑定用户</Button>
      </div>
      <tables ref="tables"  v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <user-modal ref="UserModal" @operate="ParentOperate"></user-modal>
    <user-org-bind ref="UserOrgBind" @bind="orgBind"></user-org-bind>
    <role-transfer ref="RoleTransfer" @RoleChange="ChangeRole"></role-transfer>
    <group-transfer ref="GroupTransfer" @GroupChange="ChangeGroup"></group-transfer>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import UserModal from './modal/user-template'
import UserOrgBind from './modal/user-org-bind'
import RoleTransfer from './modal/role-transfer'
import GroupTransfer from './modal/group-transfer'
import { getUser, createUser, updateUser, getOrgUsers, OrgUsersDel, changeRoleUser, changeGroupUser, OrgUsersAdd } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    UserModal,
    UserOrgBind,
    RoleTransfer,
    GroupTransfer
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        nickname: '',
        phone: '',
        email: ''
      },
      columns: [
        { title: '姓名',
          key: 'nickname'
        },
        { title: '电话', key: 'phone' },
        { title: '邮箱',
          key: 'email'
          // editable: true
        },
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
            var group = h('Button', {
              props: {
                type: 'success',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleGroup(data)
                }
              }
            }, '用户组')
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [update, del, role, group])
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
    // 用户绑定
    handleUserOrgBind () {
      this.$refs.UserOrgBind.$refs.UserOrgBind_form.resetFields()
      this.$refs.UserOrgBind.UserOrgBind_modal = true
      this.$refs.UserOrgBind.UserOrgBind_form.phone = ''
    },
    orgBind () {
      this.$refs.UserOrgBind.$refs.UserOrgBind_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          var phone = this.$refs.UserOrgBind.UserOrgBind_form.phone
          var data = {
            'type': 'phone',
            'key': phone
          }
          getUser(data).then(res => {
            if (res.data.msg === 'ok') {
              var id = this.$store.state.user.position
              var userIds = res.data.data.id
              var newData = {
                'id': id,
                'userIds': [userIds]
              }
              OrgUsersAdd(newData).then(res => {
                this.$Spin.hide()
                if (res.data.msg === 'ok') {
                  this.$Message.success('成功绑定')
                  this.$refs.UserOrgBind.UserOrgBind_modal = false
                  this.searchTable(1)
                } else {
                  this.$Message.error(res.data.msg)
                }
              }).catch(ess => {
                this.$Spin.hide()
                this.$Message.error('请联系管理员')
              })
            } else {
              this.$Spin.hide()
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
    // 更换用户组
    ChangeGroup () {
      var id = this.$refs.GroupTransfer.GroupTransfer_templateId
      var GroupArr = this.$refs.GroupTransfer.GroupKeys
      var initGroupArr = this.$refs.GroupTransfer.GroupInitData
      var groupIds = []
      GroupArr.forEach((val, i) => {
        var vKey = parseInt(val)
        initGroupArr.forEach((value, t) => {
          var dKey = value.key
          var dId = parseInt(value.id)
          if (vKey === dKey) {
            groupIds.push(dId)
          } else {}
        })
      })
      var data = {
        'id': id,
        'groupIds': groupIds
      }
      changeGroupUser(data).then(res => {
        if (res.data.msg === 'ok') {
          this.$Message.success('成功保存')
          this.searchTable(1)
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 打开用户组弹出
    handleGroup (x) {
      var data = x
      var templateId = data.id
      var udata = {
        'type': 'id',
        'key': templateId
      }
      getUser(udata).then(res => {
        if (res.data.msg === 'ok') {
          this.$refs.GroupTransfer.GroupTransfer_title = '用户组管理'
          this.$refs.GroupTransfer.GroupTransfer_templateId = templateId
          var groupArr = (!res.data.data.groupIds === true) ? [] : res.data.data.groupIds
          var groupIds = []
          var initUserArr = this.$refs.GroupTransfer.GroupInitData
          groupArr.forEach((val, i) => {
            var r_id = val
            initUserArr.forEach((value, t) => {
              var init_id = value.id
              var init_key = value.key
              if (r_id === init_id) {
                groupIds.push(init_key)
              } else {}
            })
          })
          this.$refs.GroupTransfer.GroupKeys = groupIds
          this.$refs.GroupTransfer.GroupTransfer_modal = true
        } else {
          this.$Message.error(res.data.msg)
        }
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
      changeRoleUser(data).then(res => {
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
      var udata = {
        'type': 'id',
        'key': templateId
      }
      getUser(udata).then(res => {
        if (res.data.msg === 'ok') {
          var roleArr = (!res.data.data.roleIds === true) ? [] : res.data.data.roleIds
          var roleIds = []
          var initUserArr = this.$refs.RoleTransfer.RoleInitData
          roleArr.forEach((val, i) => {
            var r_id = val
            initUserArr.forEach((value, t) => {
              var init_id = value.id
              var init_key = value.key
              if (r_id === init_id) {
                roleIds.push(init_key)
              } else {}
            })
          })
          this.$refs.RoleTransfer.RoleKeys = roleIds
          this.$refs.RoleTransfer.RoleTransfer_modal = true
        } else {
          this.$Message.error(res.data.msg)
        }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 创建用户
    handleCreate () {
      this.$refs.UserModal.$refs.UserTemplate_form.resetFields()
      this.$refs.UserModal.UserTemplate_title = '创建用户'
      this.$refs.UserModal.UserTemplate_modal = true
      this.$refs.UserModal.UserTemplate_templateId = ''
      this.$refs.UserModal.UserTemplate_form.username = ''
      this.$refs.UserModal.UserTemplate_form.password = ''
      this.$refs.UserModal.UserTemplate_form.nickname = ''
      this.$refs.UserModal.UserTemplate_form.phone = ''
      this.$refs.UserModal.UserTemplate_form.email = ''
      this.$refs.UserModal.UserTemplate_form.sex = '1'
      this.$refs.UserModal.UserTemplate_form.weixin = ''
      this.$refs.UserModal.UserTemplate_form.qq = ''
      this.$refs.UserModal.UserTemplate_judge = 'create'
    },
    // 修改用户
    handleUpdate (x) {
      var data = x
      var templateId = data.id
      var username = data.username
      var password = data.password
      var nickname = data.nickname
      var phone = data.phone
      var email = data.email
      var sex = data.sex.toString()
      var weixin = data.weixin
      var qq = data.qq
      this.$refs.UserModal.$refs.UserTemplate_form.resetFields()
      this.$refs.UserModal.UserTemplate_title = '修改用户'
      this.$refs.UserModal.UserTemplate_modal = true
      this.$refs.UserModal.UserTemplate_form.username = username
      this.$refs.UserModal.UserTemplate_form.password = password
      this.$refs.UserModal.UserTemplate_form.nickname = nickname
      this.$refs.UserModal.UserTemplate_form.phone = phone
      this.$refs.UserModal.UserTemplate_form.email = email
      this.$refs.UserModal.UserTemplate_form.sex = sex
      this.$refs.UserModal.UserTemplate_form.weixin = weixin
      this.$refs.UserModal.UserTemplate_form.qq = qq
      this.$refs.UserModal.UserTemplate_templateId = templateId
      this.$refs.UserModal.UserTemplate_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.UserModal.UserTemplate_judge
      var username = this.$refs.UserModal.UserTemplate_form.username
      var password = this.$refs.UserModal.UserTemplate_form.password
      var nickname = this.$refs.UserModal.UserTemplate_form.nickname
      var phone = this.$refs.UserModal.UserTemplate_form.phone
      var email = this.$refs.UserModal.UserTemplate_form.email
      var sex = this.$refs.UserModal.UserTemplate_form.sex
      var weixin = this.$refs.UserModal.UserTemplate_form.weixin
      var qq = this.$refs.UserModal.UserTemplate_form.qq
      var templateId = this.$refs.UserModal.UserTemplate_templateId
      var data = {
        'username': username,
        'password': password,
        'nickname': nickname,
        'phone': phone,
        'email': email,
        'sex': sex,
        'weixin': weixin,
        'qq': qq
      }
      if (judge === 'create') {
        this.$refs.UserModal.$refs.UserTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createUser(data).then(res => {
              if (res.data.msg === 'ok') {
                var id = this.$store.state.user.position
                var userIds = res.data.data.id
                var newData = {
                  'id': id,
                  'userIds': [userIds]
                }
                OrgUsersAdd(newData).then(res => {
                  this.$Spin.hide()
                  if (res.data.msg === 'ok') {
                    this.$Message.success('成功创建')
                    this.$refs.UserModal.UserTemplate_modal = false
                    this.searchTable(1)
                  } else {
                    this.$Message.error(res.data.msg)
                  }
                }).catch(ess => {
                  this.$Spin.hide()
                  this.$Message.error('请联系管理员')
                })
              } else {
                this.$Spin.hide()
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
      } else if (judge === 'update') {
        this.$refs.UserModal.$refs.UserTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = templateId
            updateUser(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                this.$refs.UserModal.UserTemplate_modal = false
                this.searchTable(1)
              } else {
                this.$Spin.hide()
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
      } else {}
    },
    // 删除用户
    handleDelete (x) {
      var data = x
      var jiacn = data.jiacn
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除用户' + jiacn
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
      obj['orgId'] = this.$store.state.user.position
      this.getUser(page, JSON.stringify(obj))
    },
    // 用户列表
    getUser (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      getOrgUsers(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除用户
    ParentNext () {
      this.$Spin.show()
      var id = this.$store.state.user.position
      var userIds = this.$refs.ready.ready_params.id
      var data = {
        'id': id,
        'userIds': [userIds]
      }
      OrgUsersDel(data).then(res => {
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
