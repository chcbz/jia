<template>
  <Modal
    :title="UserTemplate_title"
    v-model="UserTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="UserTemplate_form" :model="UserTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="用户名 (必填)" prop="username">
            <Input v-model="UserTemplate_form.username"></Input>
        </FormItem>
           <FormItem label="密码 (必填)" prop="password">
            <Input type="password" v-model="UserTemplate_form.password"></Input>
        </FormItem>
        <FormItem label="真实姓名 (必填)" prop="nickname">
            <Input v-model="UserTemplate_form.nickname"></Input>
        </FormItem>
          <FormItem label="手机号码 (必填)" prop="phone">
            <Input v-model="UserTemplate_form.phone"></Input>
        </FormItem>
           <FormItem label="电子邮箱 (必填)" prop="email">
            <Input v-model="UserTemplate_form.email"></Input>
        </FormItem>
        <FormItem label="性别">
            <RadioGroup v-model="UserTemplate_form.sex">
                <Radio label='1'>男</Radio>
                <Radio label='2'>女</Radio>
            </RadioGroup>
        </FormItem>
            <FormItem label="微信号" >
            <Input v-model="UserTemplate_form.weixin"></Input>
        </FormItem>
            <FormItem label="qq号码" >
            <Input v-model="UserTemplate_form.qq"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="UserTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      UserTemplate_title: '用户模板',
      UserTemplate_modal: false,
      UserTemplate_Validate: '',
      UserTemplate_judge: '',
      UserTemplate_templateId: '',
      UserTemplate_form: {
        username: '',
        password: '',
        nickname: '',
        phone: '',
        email: '',
        sex: '1',
        weixin: '',
        qq: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    next () {
      this.$emit('operate', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'username', 'method': 'NotNull' },
        { 'name': 'password', 'method': 'NotNull' },
        { 'name': 'name', 'method': 'NotNull' },
        { 'name': 'nickname', 'method': 'NotNull' },
        { 'name': 'phone', 'method': 'NotNull,NotMobile' },
        { 'name': 'email', 'method': 'NotNull,NotEmail' }
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

<style lang="less" scoped>

</style>
