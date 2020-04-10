<template>
  <Modal
    :title="WxMp_title"
    v-model="WxMp_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="WxMp_form" :model="WxMp_form" label-position="left" :rules="ruleValidate"  :label-width="200">
        <FormItem label="公众号名称 (必填)" prop="name">
            <Input v-model="WxMp_form.name"></Input>
        </FormItem>
        <FormItem label="公众号帐号 (必填)" prop="account">
            <Input v-model="WxMp_form.account"></Input>
        </FormItem>
            <FormItem label="令牌 (必填)" prop="token">
            <Input v-model="WxMp_form.token"></Input>
        </FormItem>
        <FormItem label="消息加解密密钥 (必填)" prop="encodingaeskey">
            <Input v-model="WxMp_form.encodingaeskey"></Input>
        </FormItem>
          <FormItem label="认证类别">
            <RadioGroup v-model="WxMp_form.level">
                <Radio label="1">普通订阅号</Radio>
                <Radio label="2">普通服务号</Radio>
                <Radio label="3">认证订阅号</Radio>
                <Radio label="4">认证服务号/认证媒体/政府订阅号</Radio>
            </RadioGroup>
        </FormItem>
            <FormItem label="原始ID (必填)" prop="original">
            <Input v-model="WxMp_form.original"></Input>
        </FormItem>
        <FormItem label="国家" prop="country">
            <Input v-model="WxMp_form.country"></Input>
        </FormItem>
        <FormItem label="省份" prop="province">
            <Input v-model="WxMp_form.province"></Input>
        </FormItem>
            <FormItem label="城市" prop="city">
            <Input v-model="WxMp_form.content"></Input>
        </FormItem>
            <FormItem label="登录账号" prop="username">
            <Input v-model="WxMp_form.username"></Input>
        </FormItem>
        <FormItem label="登录密码" prop="password">
            <Input type="password" v-model="WxMp_form.password"></Input>
        </FormItem>
              <FormItem label="开发者ID (必填)" prop="appid">
            <Input v-model="WxMp_form.appid"></Input>
        </FormItem>
        <FormItem label="开发者密码 (必填)" prop="secret">
            <Input type="password" v-model="WxMp_form.secret"></Input>
        </FormItem>
        <FormItem label="介绍" prop="signature">
            <Input v-model="WxMp_form.signature" type="textarea"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="WxMp_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      WxMp_title: '公众号',
      WxMp_modal: false,
      WxMp_Validate: '',
      WxMp_judge: '',
      WxMp_acid: '',
      WxMp_form: {
        token: '',
        encodingaeskey: '',
        level: '1',
        name: '',
        account: '',
        original: '',
        signature: '',
        country: '',
        province: '',
        city: '',
        username: '',
        password: '',
        appid: '',
        secret: ''
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
        { 'name': 'name', 'method': 'NotNull' },
        { 'name': 'account', 'method': 'NotNull' },
        { 'name': 'token', 'method': 'NotNull' },
        { 'name': 'encodingaeskey', 'method': 'NotNull' },
        { 'name': 'original', 'method': 'NotNull' },
        { 'name': 'appid', 'method': 'NotNull' },
        { 'name': 'secret', 'method': 'NotNull' }
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

<style scoped>
  /deep/ .ivu-modal{
    width: 780px!important;
    top: 20px;
  }
</style>
