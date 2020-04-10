<style lang="less">
  @import './login.less';
</style>

<template>
  <div class="login">
    <div class="login-con">
      <Card icon="log-in" title="欢迎登录" :bordered="false">
        <div class="form-con">
          <login-form ref="LoginForm" @submitHandle="handleSubmit"></login-form>
        </div>
      </Card>
    </div>
  </div>
</template>

<script>
import LoginForm from '_c/login-form'
import { mapActions } from 'vuex'
import { localRead, localSave } from '@/libs/util'
export default {
  components: {
    LoginForm
  },
  methods: {
    ...mapActions([
      'handleLogin',
      'getUserInfo'
    ]),
    handleSubmit () {
      var type = this.$refs.LoginForm.switch_type
      var _this = this
      var currentName = localRead('currentName') ? localRead('currentName') : this.$config.homeName
      if (type === 'user') {
        this.$refs.LoginForm.$refs.userForm.validate((valid) => {
          if (valid) {
            var code = this.$refs.LoginForm.userForm.code.toUpperCase()
            var checkcode = this.$refs.LoginForm.userForm.checkcode.toUpperCase()
            if (code === checkcode) {
              var userName = this.$refs.LoginForm.userForm.userName
              var password = this.$refs.LoginForm.userForm.password
              this.handleLogin({ userName, password, type, _this }).then(res => {
                this.getUserInfo().then(res => {
                  var tagNavList = this.$store.state.app.tagNavList
                  tagNavList.splice(0, tagNavList.length)
                  if (localRead('currentName')) {} else {
                    localSave('tagNaveList', '')
                  }
                  this.$router.push({
                    name: currentName
                  })
                  window.location.reload()
                })
              })
            } else {
              this.$Message.error('验证码有误！')
            }
          }
        })
      } else if (type === 'mobile') {
        this.$refs.LoginForm.$refs.mobileForm.validate((valid) => {
          if (valid) {
            var userName = 'mb-' + this.$refs.LoginForm.mobileForm.mobile
            var password = this.$refs.LoginForm.mobileForm.code
            this.handleLogin({ userName, password, type, _this }).then(res => {
              this.getUserInfo().then(res => {
                var tagNavList = this.$store.state.app.tagNavList
                tagNavList.splice(0, tagNavList.length)
                if (localRead('currentName')) {} else {
                  localSave('tagNaveList', '')
                }
                this.$router.push({
                  name: currentName
                })
                window.location.reload()
              })
            })
          }
        })
      } else {}
    }
    // handleSubmit ({ userName, password }) {
    //   this.handleLogin({ userName, password }).then(res => {
    //     this.getUserInfo().then(res => {
    //       this.$router.push({
    //         name: this.$config.homeName
    //       })
    //     })
    //   })
    // }
  }
}
</script>

<style>

</style>
