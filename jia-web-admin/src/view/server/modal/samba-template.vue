<template>
  <Modal
    :title="samba_title"
    v-model="samba_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="samba_form" :model="samba_form" label-position="left" :rules="ruleValidate"  :label-width="150">
          <FormItem label="基本DC (必填)" prop="base">
            <Input type="text" v-model="samba_form.base"></Input>
        </FormItem>
           <FormItem label="管理员用户名 (必填)" prop="user">
            <Input type="text" v-model="samba_form.user"></Input>
        </FormItem>
           <FormItem label="管理员密码 (必填)" prop="password">
            <Input type="password" v-model="samba_form.password"></Input>
        </FormItem>
           <FormItem label="LDAP服务器地址 (必填)" prop="url">
            <Input type="text" v-model="samba_form.url"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="samba_modal=false">取消</Button>
      <Button type="primary" size="large" @click="sambaNext">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      samba_title: 'samba服务',
      samba_modal: false,
      samba_Validate: '',
      samba_judge: '',
      samba_Id: '',
      samba_params: [],
      samba_form: {
        base: '',
        user: '',
        password: '',
        url: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    sambaNext () {
      this.$emit('sambaNext', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'base', 'method': 'NotNull' },
        { 'name': 'user', 'method': 'NotNull' },
        { 'name': 'password', 'method': 'NotNull' },
        { 'name': 'url', 'method': 'NotNull' }
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
