<template>
  <Modal
    :title="WxPay_title"
    v-model="WxPay_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="WxPay_form" :model="WxPay_form" label-position="left" :rules="ruleValidate"  :label-width="200">
        <FormItem label="支付平台名称 (必填)" prop="name">
            <Input v-model="WxPay_form.name"></Input>
        </FormItem>
        <FormItem label="支付平台帐号 (必填)" prop="account">
            <Input v-model="WxPay_form.account"></Input>
        </FormItem>
        <FormItem label="国家" prop="country">
            <Input v-model="WxPay_form.country"></Input>
        </FormItem>
        <FormItem label="省份" prop="province">
            <Input v-model="WxPay_form.province"></Input>
        </FormItem>
            <FormItem label="城市" prop="city">
            <Input v-model="WxPay_form.content"></Input>
        </FormItem>
            <FormItem label="登录账号 (必填)" prop="username">
            <Input v-model="WxPay_form.username"></Input>
        </FormItem>
        <FormItem label="登录密码 (必填)" prop="password">
            <Input type="password" v-model="WxPay_form.password"></Input>
        </FormItem>
              <FormItem label="开发者ID (必填)" prop="appId">
            <Input v-model="WxPay_form.appId"></Input>
        </FormItem>
             <FormItem label="开发者子ID" prop="subAppId">
            <Input v-model="WxPay_form.subAppId"></Input>
        </FormItem>
              <FormItem label="商户ID (必填)" prop="mchId">
            <Input v-model="WxPay_form.mchId"></Input>
        </FormItem>
        <FormItem label="商户密钥 (必填)" prop="mchKey">
            <Input type="password" v-model="WxPay_form.mchKey"></Input>
        </FormItem>
           <FormItem label="子商户ID" prop="subMchId">
            <Input v-model="WxPay_form.subMchId"></Input>
        </FormItem>
        <FormItem label="回调地址" prop="notifyUrl">
            <Input v-model="WxPay_form.notifyUrl"></Input>
        </FormItem>
                <FormItem label="认证类别">
            <RadioGroup v-model="WxPay_form.tradeType">
                <Radio label="JSAPI">公众号支付</Radio>
                <Radio label="NATIVE">原生扫码支付</Radio>
                <Radio label="APP">app支付</Radio>
            </RadioGroup>
        </FormItem>
                <FormItem label="签名方式">
            <RadioGroup v-model="WxPay_form.signType">
                <Radio label="HMAC_SHA256">HMAC_SHA256</Radio>
                <Radio label="MD5">MD5</Radio>
            </RadioGroup>
        </FormItem>
            <FormItem label="证书文件路径" prop="keyPath">
            <Input v-model="WxPay_form.keyPath"></Input>
        </FormItem>
            <FormItem label="证书文件内容" prop="keyContent">
            <Input v-model="WxPay_form.keyContent"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="WxPay_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      WxPay_title: '公众号',
      WxPay_modal: false,
      WxPay_Validate: '',
      WxPay_judge: '',
      WxPay_acid: '',
      WxPay_form: {
        name: '',
        account: '',
        country: '',
        province: '',
        city: '',
        username: '',
        password: '',
        appId: '',
        subAppId: '',
        mchId: '',
        mchKey: '',
        subMchId: '',
        notifyUrl: '',
        tradeType: '',
        signType: '',
        keyPath: '',
        keyContent: ''
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
        { 'name': 'username', 'method': 'NotNull' },
        { 'name': 'password', 'method': 'NotNull' },
        { 'name': 'appId', 'method': 'NotNull' },
        { 'name': 'mchId', 'method': 'NotNull' },
        { 'name': 'mchKey', 'method': 'NotNull' }
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
    width: 600px!important;
    top: 20px;
  }
</style>
