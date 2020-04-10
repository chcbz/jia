<template>
  <div>
    <Form ref="userForm" v-show="switch_type=='user'" :model="userForm" :rules="userRules" @keydown.enter.native="handleSubmit">
      <FormItem prop="userName">
        <Input v-model="userForm.userName" placeholder="请输入用户名">
        <span slot="prepend">
          <Icon :size="16" type="ios-person"></Icon>
        </span>
        </Input>
      </FormItem>
      <FormItem prop="password">
        <Input type="password" v-model="userForm.password" placeholder="请输入密码">
        <span slot="prepend">
          <Icon :size="14" type="md-lock"></Icon>
        </span>
        </Input>
      </FormItem>
      <FormItem prop="code">
        <div  style="    display: flex;    width: 100%;">
          <Input  v-model="userForm.code" placeholder="请输入验证码" style="width: 80%">
          <span slot="prepend">
          <Icon :size="14" type="ios-key"></Icon>
        </span>
          </Input>
          <Button type="error" style="margin-left: 5px;" size="small"  @click="createCode">{{userForm.checkcode}}</Button>
        </div>
      </FormItem>
    </Form>
    <Form ref="mobileForm" v-show="switch_type=='mobile'" :model="mobileForm" :rules="mobileRules" @keydown.enter.native="handleSubmit">
      <FormItem prop="mobile">
        <Input v-model="mobileForm.mobile" placeholder="请输入手机号">
        <span slot="prepend">
          <Icon :size="16" type="md-phone-portrait"></Icon>
        </span>
        </Input>
      </FormItem>
      <FormItem prop="code">
        <div  style="    display: flex;    width: 100%;">
          <Input  v-model="mobileForm.code" placeholder="请输入验证码" style="width: 80%">
          <span slot="prepend">
          <Icon :size="14" type="ios-key"></Icon>
        </span>
          </Input>
          <Button type="error" style="margin-left: 5px;" size="small"  @click="getSms()" :disabled="SmsDis">{{SmsTitle}}</Button>
        </div>
      </FormItem>
    </Form>
    <Form>
      <FormItem>
        <Button @click="handleSubmit" type="primary" long>登录</Button>
      </FormItem>
    </Form>
    <div class="register_div">
      <span @click="goRegister()">马上注册></span>
    </div>
    <div class="switch_log">
      <li class="log_title">其他登录方式:</li>
      <ul>
        <li><img src="../../assets/images/user.png"    class="hand img-thumbnail rounded-circle"  @click="loginType('user')"></li>
        <li><img src="../../assets/images/mobile.png"  class="hand img-thumbnail rounded-circle"  @click="loginType('mobile')"></li>
        <!--<li><img src="../../assets/images/wechat.png"  class="hand img-thumbnail rounded-circle"  @click="loginType('wechat')"></li>-->
      </ul>
    </div>
  </div>
</template>
<script>
import { createsmsCode } from '@/api/data'
export default {
  name: 'LoginForm',
  props: {
    userNameRules: {
      type: Array,
      default: () => {
        return [
          { required: true, message: '账号不能为空', trigger: 'blur' }
        ]
      }
    },
    passwordRules: {
      type: Array,
      default: () => {
        return [
          { required: true, message: '密码不能为空', trigger: 'blur' }
        ]
      }
    },
    codeRules: {
      type: Array,
      default: () => {
        return [
          { required: true, message: '验证码不能为空', trigger: 'blur' }
        ]
      }
    }
  },
  data () {
    return {
      userForm: {
        userName: '',
        password: '',
        code: '',
        checkcode: ''
      },
      mobileForm: {
        mobile: '',
        code: ''
      },
      switch_type: 'user',
      mobileRules: {},
      countdown: 60,
      SmsTitle: '获取验证码',
      SmsDis: false
    }
  },
  computed: {
    userRules () {
      return {
        userName: this.userNameRules,
        password: this.passwordRules,
        code: this.codeRules
      }
    }
  },
  methods: {
    initForm () {
      var mobile_arr = [
        { 'name': 'mobile', 'method': 'NotNull,NotMobile' },
        { 'name': 'code', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(mobile_arr)
      this.mobileRules = this.$constant.CreateVerify(strArr)
    },
    goRegister () {
      this.$router.push({
        name: 'register'
      })
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
      this.$refs.mobileForm.validateField('mobile', (valid) => {
        if (valid) {} else {
          this.$constant.getWorkToken().then((data) => {
            const post_arr = {
              'phone': this.mobileForm.mobile,
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
    // 切换登录方式
    loginType (x) {
      this.$refs.userForm.resetFields()
      this.$refs.mobileForm.resetFields()
      this.switch_type = x
      if (x === 'wechat') {
        this.$router.push({ name: 'wechatLogin' })
      } else {}
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
    },
    /* 提交 */
    handleSubmit () {
      this.$emit('submitHandle')
    }
  },
  created () {
    this.initForm()
    this.createCode()
  }
}
</script>

<style scoped>
  .register_div{
   text-align: right;
    color: #348EED;
  }
  .register_div span{
    cursor: pointer;
    margin-right: 10px;
  }
  .switch_log {
    display: block;
    text-align: center;
    border-top: 0.01rem solid #d8d8d8;
  }
  .log_title{
    font-size: 12px;
    color: #7f8790;
    letter-spacing: 0.09em;
    margin-top: 12px!important;
  }
  .switch_log ul {
    display: inline-flex;
    margin: 10px auto 0px auto;
  }
  .switch_log ul li img {
    max-width: 60%;
  }
  .hand {
    cursor: pointer;
  }
  .rounded-circle {
    border-radius: 50%!important;
  }
  .switch_log ul li img {
    max-width: 60%;
    /* background-color: #f9f9f9d9; */
  }
  .switch_log ul li img:hover {
    background-color: #efefefd9;
  }
  .switch_log li {
    margin: 0;
  }
  .hand {
    cursor: pointer;
  }
  .rounded-circle {
    border-radius: 50%!important;
  }
  .img-thumbnail {
    padding: .25rem;
    background-color: #fff;
    border: 1px solid #dee2e6;
    border-radius: .25rem;
    max-width: 100%;
    height: auto;
  }
</style>
