<template>
  <Modal
    :title="LDAPAccount_title"
    v-model="LDAPAccount_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="LDAPAccount_form" :model="LDAPAccount_form" label-position="left" :rules="ruleValidate"  :label-width="120">
             <FormItem label="账户姓名(必填)" prop="cn">
            <Input type="text" v-model="LDAPAccount_form.cn" :disabled="LDAPAccount_judge=='updatePassword'"></Input>
        </FormItem>
          <FormItem label="账户UID(必填)" prop="uid">
            <Input type="text" v-model="LDAPAccount_form.uid" :disabled="LDAPAccount_judge=='update'||LDAPAccount_judge=='updatePassword'"></Input>
        </FormItem>
            <FormItem label="组ID(必填)" prop="gidNumber">
                <Select v-model="LDAPAccount_form.gidNumber" filterable :disabled="LDAPAccount_judge=='updatePassword'">
                  <Option v-for="(option, v) in gidNumberArr" :value="option.gidNumber" :key="v">{{option.description}}</Option>
                </Select>
              </FormItem>
           <FormItem label="密码(必填)" prop="userPassword">
            <Input type="password" v-model="LDAPAccount_form.userPassword" :disabled="LDAPAccount_judge=='update'"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="LDAPAccount_modal=false">取消</Button>
      <Button type="primary" size="large" @click="LDAPAccountNext">确定</Button>
    </div>
  </Modal>
</template>

<script>
import { LdapGroupList } from '@/api/data'
export default {
  props: {
    serverId: {
      type: Number
    }
  },
  data () {
    return {
      LDAPAccount_title: 'LDAPAccount服务',
      LDAPAccount_modal: false,
      LDAPAccount_Validate: '',
      LDAPAccount_judge: '',
      LDAPAccount_Id: '',
      LDAPAccount_params: [],
      gidNumberArr: [],
      LDAPAccount_form: {
        cn: '',
        uid: '',
        gidNumber: '',
        userPassword: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    // LDAP账户组列表
    LdapGroupList () {
      var serverId = this.serverId
      var data = {
        'serverId': serverId
      }
      LdapGroupList(data).then(res => {
        var arr = res.data.data
        this.gidNumberArr = arr
      })
    },
    LDAPAccountNext () {
      this.$emit('LDAPAccountNext', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'cn', 'method': 'NotNull' },
        { 'name': 'uid', 'method': 'NotNull' },
        { 'name': 'gidNumber', 'method': 'NotNull' },
        { 'name': 'userPassword', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      this.LdapGroupList()
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less">

</style>
