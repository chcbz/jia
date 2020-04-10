<template>
  <div>
    <Card>
      <Button style="margin: 10px 0;" type="success" @click="handleCreate">新增LDAP账户组</Button>
      <tables ref="tables"   border v-model="tableData" :columns="columns"/>
    </Card>
    <ldap-group-modal ref="ldapGroupModal" @LDAPGroupNext="goLdapGroup"></ldap-group-modal>
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import Tables from '_c/tables'
import ldapGroupModal from '../modal/ldap-group-template'
import Ready from '_c/modal/ready'
import { LdapGroupList, createLdapGroup, updateLdapGroup, delLdapGroup } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    ldapGroupModal
  },
  data () {
    return {
      serverId: '',
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: 'cn', key: 'cn' },
        { title: 'gidNumber', key: 'gidNumber' },
        // { title: 'memberUid', key: 'memberUid' },
        { title: 'description', key: 'description' },
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
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [update, del])
            return result
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // LDAP账户组列表
    LdapGroupList () {
      const serverId = this.serverId
      var data = {
        'serverId': serverId
      }
      LdapGroupList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.data.length
        this.$refs.tables.insidePageSize = res.data.data.length
      })
    },
    // 新增LDAP账户组
    handleCreate () {
      this.$refs.ldapGroupModal.$refs.LDAPGroup_form.resetFields()
      this.$refs.ldapGroupModal.LDAPGroup_title = '创建LDAP账户组'
      this.$refs.ldapGroupModal.LDAPGroup_modal = true
      this.$refs.ldapGroupModal.LDAPGroup_form.cn = ''
      this.$refs.ldapGroupModal.LDAPGroup_form.description = ''
      this.$refs.ldapGroupModal.LDAPGroup_judge = 'create'
    },
    // 修改
    handleUpdate (x) {
      this.$refs.ldapGroupModal.$refs.LDAPGroup_form.resetFields()
      this.$refs.ldapGroupModal.LDAPGroup_title = '修改LDAP账户组'
      this.$refs.ldapGroupModal.LDAPGroup_modal = true
      this.$refs.ldapGroupModal.LDAPGroup_Id = x.id
      this.$refs.ldapGroupModal.LDAPGroup_form.cn = x.cn
      this.$refs.ldapGroupModal.LDAPGroup_form.description = x.description
      this.$refs.ldapGroupModal.LDAPGroup_judge = 'update'
    },
    goLdapGroup () {
      var judge = this.$refs.ldapGroupModal.LDAPGroup_judge
      const serverId = this.serverId
      var cn = this.$refs.ldapGroupModal.LDAPGroup_form.cn
      var description = this.$refs.ldapGroupModal.LDAPGroup_form.description
      var data = {
        'serverId': serverId,
        'cn': cn,
        'description': description
      }
      if (judge === 'create') {
        this.$refs.ldapGroupModal.$refs.LDAPGroup_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createLdapGroup(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.ldapGroupModal.LDAPGroup_modal = false
                this.LdapGroupList()
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
        this.$refs.ldapGroupModal.$refs.LDAPGroup_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            updateLdapGroup(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.ldapGroupModal.LDAPGroup_modal = false
                this.LdapGroupList()
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
    // 删除LDAP账户组
    handleDelete (x) {
      var data = x
      var name = data.cn
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var cn = this.$refs.ready.ready_params.cn
      const serverId = this.serverId
      var data = {
        'serverId': serverId,
        'cn': cn
      }
      delLdapGroup(data).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.LdapGroupList()
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
    this.LdapGroupList()
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
