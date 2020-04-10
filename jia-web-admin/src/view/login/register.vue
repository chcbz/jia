<template>
  <div class="layout">
    <Layout>
      <Header :style="{position: 'fixed', width: '100%'}">
        <Menu mode="horizontal" theme="light" active-name="1">
          <div class="layout-logo">
            <img :src="maxLogo"  />
          </div>
        </Menu>
      </Header>
      <Card :style="{margin: '78px 20px 0', background: '#fff', minHeight: '500px'}">
        <!--<Row>-->
          <!--<Col  :sm="14" :md="14" :lg="8" offset="1">-->
        <div class="form_wrap">
          <h2>欢迎注册Jiā顺服务平台</h2>
          <!--注册表单-->
          <Form ref="userForm" :model="userForm" label-position="left" :rules="ruleValidate"  :label-width="120">
            <FormItem label="用户名" prop="username">
              <Input v-model="userForm.username" placeholder="请输入用户名"></Input>
            </FormItem>
            <FormItem label="密码" prop="password">
              <Input type="password" v-model="userForm.password" placeholder="请输入密码"></Input>
            </FormItem>
            <FormItem label="再次输入密码" :rules="{validator: PasswordCheck, trigger: 'blur'}" prop="rePassword">
              <Input type="password" v-model="userForm.rePassword"  placeholder="请再次输入密码"></Input>
            </FormItem>
            <FormItem label="组织编码" prop="orgCode">
              <Input v-model="userForm.orgCode" placeholder="请输入组织编码"></Input>
            </FormItem>
            <FormItem label="组织名称" prop="orgName">
              <Input v-model="userForm.orgName" placeholder="请输入组织名称"></Input>
            </FormItem>
            <FormItem label="真实姓名" prop="orgName">
              <Input v-model="userForm.nickname" placeholder="请输入真实姓名"></Input>
            </FormItem>
            <FormItem label="手机号码" prop="mobile">
              <Input v-model="userForm.mobile" placeholder="请输入手机号码"></Input>
            </FormItem>
            <FormItem label="手机验证码"  prop="mobileCode">
              <div  style="    display: flex;    width: 100%;">
                <Input  v-model="userForm.mobileCode" placeholder="请输入手机验证码" style="width: 80%"></Input>
                <Button type="success" style="margin-left: 5px;" size="small"  @click="getSms()" :disabled="SmsDis">{{SmsTitle}}</Button>
              </div>
            </FormItem>
            <FormItem label="电子邮箱" prop="email">
              <Input v-model="userForm.email" placeholder="请输入电子邮箱"></Input>
            </FormItem>
            <FormItem label="验证码"  :rules="{validator: CodeCheck, trigger: 'blur'}"  prop="code">
              <div  style="display: flex;  width: 100%;">
                <Input  v-model="userForm.code" placeholder="请输入验证码" style="width: 80%"></Input>
                <Button type="error" style="margin-left: 5px;" size="small"  @click="createCode">{{userForm.checkcode}}</Button>
              </div>
            </FormItem>
          </Form>
          <div class="operate">
            <Button type="primary" size="large" @click="registerUser()">注册</Button>
          </div>
        </div>
          <!--</Col>-->
        <!--</Row>-->
        <!--<div class="step_div">-->
          <!--<Steps :current="steps" :status="steps_status">-->
            <!--<Step title="进行中" content="填写注册信息"></Step>-->
            <!--<Step title="待进行" content="等待系统校验"></Step>-->
            <!--<Step title="已完成" content="成功注册"></Step>-->
          <!--</Steps>-->
        <!--</div>-->
      </Card>
      <div class="form_footer"><span>粤ICP备18065644号-1东莞市顺为软件有限公司™2016©</span></div>
    </Layout>
  </div>
</template>

<script>
import maxLogo from '@/assets/images/logo.png'
import { createsmsCode, smsValidateCode } from '@/api/data'
import { registerClient } from '@/api/user'

export default {
  components: {},
  data () {
    return {
      maxLogo,
      UserTemplate_Validate: '',
      UserTemplate_judge: '',
      UserTemplate_templateId: '',
      // steps: 0,
      // steps_status: 'process',
      userForm: {
        username: '',
        password: '',
        rePassword: '',
        orgCode: '',
        orgName: '',
        mobile: '',
        email: '',
        nickname: '',
        code: '',
        mobileCode: ''
      },
      mobileRules: {},
      countdown: 60,
      SmsTitle: '获取验证码',
      SmsDis: false,
      ruleValidate: {
      }
    }
  },
  methods: {
    // 用户注册
    registerUser () {
      // this.steps = this.steps + 1
      this.$refs.userForm.validate((valid) => {
        if (valid) {
          var username = this.userForm.username
          var password = this.userForm.password
          var orgCode = this.userForm.orgCode
          var orgName = this.userForm.orgName
          var mobile = this.userForm.mobile
          var email = this.userForm.email
          var nickname = this.userForm.nickname
          var smsCode = this.userForm.mobileCode
          this.$constant.getWorkToken().then((data) => {
            const post_arr = {
              'phone': mobile,
              'smsType': 1,
              'smsCode': smsCode
            }
            var access_token = data.access_token
            smsValidateCode(post_arr, access_token).then((res) => {
              if (res.data.msg === 'ok') {
                const data = {
                  'username': username,
                  'password': password,
                  'orgCode': orgCode,
                  'orgName': orgName,
                  'nickname': nickname,
                  'email': email,
                  'phone': mobile
                }
                registerClient(data, access_token).then((res) => {
                  if (res.data.code === 'E0') {
                    this.$Message.success('注册成功')
                    this.$router.push({ name: 'login' })
                    // this.steps = this.steps + 1
                    // this.steps_status = 'finish'
                  } else {
                    // this.steps_status = 'error'
                    this.$Message.error(res.data.msg)
                  }
                }).catch(ess => {
                  // this.steps_status = 'error'
                  this.$Message.error('请联系管理员')
                })
              } else {
                // this.steps_status = 'error'
                this.$Message.error('手机验证码有误')
              }
            })
          })
        } else {
          this.steps_status = 'error'
        }
      })
    },
    // 验证码判断
    CodeCheck (rule, y, callback) {
      var code = this.userForm.code.toUpperCase()
      var checkcode = this.userForm.checkcode.toUpperCase()
      if (y === '') {
        return callback(new Error('该项为必填项'))
      } else if (code !== checkcode) {
        return callback(new Error('验证码输入错误'))
      } else {
        callback()
      }
    },
    // 验证密码是否2次输入一样
    PasswordCheck (rule, y, callback) {
      var password = this.userForm.password
      if (y === '') {
        return callback(new Error('该项为必填项'))
      } else if (password !== y) {
        return callback(new Error('两次密码输入不一致'))
      } else {
        callback()
      }
    },
    // 初始化
    initVform () {
      var v_arr = [
        { 'name': 'username', 'method': 'NotNull' },
        { 'name': 'password', 'method': 'NotNull' },
        { 'name': 'name', 'method': 'NotNull' },
        { 'name': 'orgCode', 'method': 'NotNull' },
        { 'name': 'orgName', 'method': 'NotNull' },
        { 'name': 'nickname', 'method': 'NotNull' },
        { 'name': 'mobile', 'method': 'NotNull,NotMobile' },
        { 'name': 'mobileCode', 'method': 'NotNull' },
        { 'name': 'email', 'method': 'NotNull,NotEmail' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      this.createCode()
    },
    // 点击验证码倒计时
    settime () {
      if (this.countdown === 0) {
        this.SmsDis = false
        this.SmsTitle = '获取验证码'
        this.countdown = 60
      } else {
        this.SmsDis = true
        this.SmsTitle = '重新发送' + this.countdown + '秒'
        this.countdown--
        var _this = this
        setTimeout(() => {
          _this.settime()
        }, 1000)
      }
    },
    // 获取手机验证码
    getSms () {
      this.$refs.userForm.validateField('mobile', (valid) => {
        if (valid) {} else {
          this.$constant.getWorkToken().then((data) => {
            const post_arr = {
              'phone': this.userForm.mobile,
              'smsType': 1
            }
            var access_token = data.access_token
            createsmsCode(post_arr, access_token).then((res) => {
              if (res.data.code === 'E0') {
                this.$Message.success('发送成功,请注意查收!')
              } else {
                this.$Message.error(res.data.msg)
              }
              this.settime()
            })
          })
        }
      })
    },
    /* 生成验证码 */
    createCode () {
      var code = ''
      var codeLength = 4 // 验证码的长度
      var codeChars = [2, 3, 4, 5, 6, 7, 8, 9,
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'm', 'n', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'] // 所有候选组成验证码的字符，当然也可以用中文的
      for (var i = 0; i < codeLength; i++) {
        var charNum = Math.floor(Math.random() * 52)
        code += codeChars[charNum]
      }
      this.userForm.checkcode = code
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style scoped>
  /deep/.ivu-layout-header{
    position: absolute!important;
    padding: 0;
  }
  /deep/.ivu-card{
    margin: 80px auto 0!important;
    width: 1000px!important;
  }
  .layout{
    border: 1px solid #d7dde4;
    background: #f5f7f9;
    position: relative;
    border-radius: 4px;
    height: 100%;
    overflow: auto;
  }
  .layout-logo{
    border-radius: 3px;
    float: left;
    position: relative;
    top: 5px;
    left: 20px;
  }
  .layout-logo img{
    width: auto;
    height: auto;
    max-height: 48px;
  }
  /*.step_div{*/
    /*margin-left: 55px;*/
  /*}*/
  .form_wrap{
    margin: 0 auto;
    width: 500px;
    text-align: center;
  }
  .form_wrap h2{
    padding: 20px 0 30px;
    font-size: 30px;
    color: #373d41;
    letter-spacing: 1px;
    line-height: 36px;
  }
  .operate{
    margin-left: 0;
    margin-bottom: 20px;
  }
  .operate button{
    width: 100%;
  }
  .form_footer{
    padding: 10px;
    text-align: center;
  }
</style>
