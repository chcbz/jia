<template>
  <div>
    <Card>
      <Button style="margin: 10px 0;" type="success" @click="handleCreate">新增LDAP系统账户</Button>
      <tables ref="tables"   border v-model="tableData" :columns="columns"/>
      <Button style="margin: 10px 0;" type="info" @click="nextPage" :disabled="noPage">下一页</Button>
    </Card>
    <ldap-account-modal ref="ldapAccountModal" :serverId="parseInt(serverId)" @LDAPAccountNext="goLdapAccount"></ldap-account-modal>
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import Tables from '_c/tables'
import ldapAccountModal from '../modal/ldap-accout-template'
import Ready from '_c/modal/ready'
import { LdapAccountList, createLdapAccount, updateLdapAccount, updateLdapAccountPwd, delLdapAccount } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    ldapAccountModal
  },
  data () {
    return {
      serverId: '',
      pageNum: 1,
      pageSize: 10,
      nextTag: null,
      noPage: false,
      columns: [
        { title: 'cn', key: 'cn' },
        { title: 'uid', key: 'uid' },
        { title: 'gidNumber', key: 'gidNumber' },
        { title: 'uidNumber', key: 'uidNumber' },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var updateInfo = h('Button', {
              props: {
                type: 'info',
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleUpdate(data, 'info')
                }
              }
            }, '修改信息')
            var updatePassWord = h('Button', {
              props: {
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleUpdate(data, 'password')
                }
              }
            }, '修改密码')
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
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [updateInfo, updatePassWord, del])
            return result
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // LDAP系统账户列表
    LdapAccountList () {
      const serverId = this.serverId
      const pageSize = this.pageSize
      var nextTag = this.nextTag
      var data = {
        'pageSize': pageSize,
        'nextTag': nextTag,
        'search': {
          'serverId': serverId
        }
      }
      LdapAccountList(data).then(res => {
        var resArr = res.data.data
        var oldArr = this.tableData
        var newArr = [...oldArr, ...resArr]
        this.tableData = newArr
        var nextTag = res.data.hasOwnProperty('nextTag')
        if (nextTag) {
          this.nextTag = res.data.nextTag
        } else {
          this.noPage = true
        }
        this.$refs.tables.insidePageCount = res.data.data.length
        this.$refs.tables.insidePageSize = res.data.data.length
      })
    },
    // 下一页
    nextPage () {
      this.LdapAccountList()
    },
    // 新增LDAP系统账户
    handleCreate () {
      this.$refs.ldapAccountModal.$refs.LDAPAccount_form.resetFields()
      this.$refs.ldapAccountModal.LDAPAccount_title = '创建LDAP系统账户'
      this.$refs.ldapAccountModal.LDAPAccount_modal = true
      this.$refs.ldapAccountModal.LDAPAccount_form.cn = ''
      this.$refs.ldapAccountModal.LDAPAccount_form.uid = ''
      this.$refs.ldapAccountModal.LDAPAccount_form.gidNumber = ''
      this.$refs.ldapAccountModal.LDAPAccount_form.userPassword = ''
      this.$refs.ldapAccountModal.LDAPAccount_judge = 'create'
    },
    // 修改
    handleUpdate (x, type) {
      this.$refs.ldapAccountModal.$refs.LDAPAccount_form.resetFields()
      this.$refs.ldapAccountModal.LDAPAccount_title = '修改LDAP系统账户'
      this.$refs.ldapAccountModal.LDAPAccount_modal = true
      this.$refs.ldapAccountModal.LDAPAccount_Id = x.id
      this.$refs.ldapAccountModal.LDAPAccount_form.cn = x.cn
      this.$refs.ldapAccountModal.LDAPAccount_form.uid = x.uid
      this.$refs.ldapAccountModal.LDAPAccount_form.gidNumber = x.gidNumber
      this.$refs.ldapAccountModal.LDAPAccount_form.userPassword = x.userPassword
      this.$refs.ldapAccountModal.LDAPAccount_judge = (type === 'info') ? 'update' : 'updatePassword'
    },
    goLdapAccount () {
      var judge = this.$refs.ldapAccountModal.LDAPAccount_judge
      const serverId = this.serverId
      var cn = this.$refs.ldapAccountModal.LDAPAccount_form.cn
      var uid = this.$refs.ldapAccountModal.LDAPAccount_form.uid
      var gidNumber = this.$refs.ldapAccountModal.LDAPAccount_form.gidNumber
      var userPassword = this.$refs.ldapAccountModal.LDAPAccount_form.userPassword
      var data = {
        'uid': uid,
        'serverId': serverId
      }
      if (judge === 'create') {
        this.$refs.ldapAccountModal.$refs.LDAPAccount_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['cn'] = cn
            data['gidNumber'] = gidNumber
            data['userPassword'] = userPassword
            createLdapAccount(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.ldapAccountModal.LDAPAccount_modal = false
                window.location.reload()
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
        this.$refs.ldapAccountModal.$refs.LDAPAccount_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['cn'] = cn
            data['gidNumber'] = gidNumber
            updateLdapAccount(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.ldapAccountModal.LDAPAccount_modal = false
                window.location.reload()
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else if (judge === 'updatePassword') {
        this.$refs.ldapAccountModal.$refs.LDAPAccount_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['password'] = userPassword
            updateLdapAccountPwd(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.ldapAccountModal.LDAPAccount_modal = false
                window.location.reload()
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
    // 删除LDAP系统账户
    handleDelete (x) {
      var data = x
      var name = data.uid
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var uid = this.$refs.ready.ready_params.uid
      const serverId = this.serverId
      var data = {
        'serverId': serverId,
        'uid': uid
      }
      delLdapAccount(data).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        window.location.reload()
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    // 获取ServerId
    getServerId () {
      var w_url = window.location.href // 获取当前页面url
      var arr = w_url.split('/')
      var serverId = arr[arr.length - 1] // 地址栏链接页面
      this.serverId = parseInt(serverId)
    }
  },
  computed: {},
  mounted () {
    this.LdapAccountList()
  },
  created () {
    this.getServerId()
  },
  watch: {
    '$route' (to, from) {
      this.getServerId()
    }
  }
}
</script>

<style scoped>
/deep/.table_detailed,/deep/.table_page{
  display: none;
}
</style>
