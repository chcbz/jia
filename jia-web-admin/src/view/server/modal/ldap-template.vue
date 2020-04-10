<template>
  <Modal
    :title="LDAP_title"
    v-model="LDAP_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="LDAP_form" :model="LDAP_form" label-position="left" :rules="ruleValidate"  :label-width="120">
          <FormItem label="基本DC (必填)" prop="base">
            <Input type="text" v-model="LDAP_form.base"></Input>
        </FormItem>
           <FormItem label="管理员用户名 (必填)" prop="user">
            <Input type="text" v-model="LDAP_form.user"></Input>
        </FormItem>
           <FormItem label="管理员密码 (必填)" prop="password">
            <Input type="password" v-model="LDAP_form.password"></Input>
        </FormItem>
           <FormItem label="端口 (必填)" prop="port">
            <Input type="text" v-model="LDAP_form.port"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="LDAP_modal=false">取消</Button>
      <Button type="primary" size="large" @click="LdapNext">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      LDAP_title: 'LDAP服务',
      LDAP_modal: false,
      LDAP_Validate: '',
      LDAP_judge: '',
      LDAP_Id: '',
      LDAP_params: [],
      LDAP_form: {
        base: '',
        user: '',
        password: '',
        port: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    LdapNext () {
      this.$emit('ldapNext', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'base', 'method': 'NotNull' },
        { 'name': 'user', 'method': 'NotNull' },
        { 'name': 'password', 'method': 'NotNull' },
        { 'name': 'port', 'method': 'NotNull,NotInt' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less">

</style>
