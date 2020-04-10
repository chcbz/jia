<template>
  <div>
    <Card>
      <Row>
        <Col offset="1"  :sm="14" :md="14" :lg="8">
          <Form ref="User_form" :model="User_form" label-position="left" :rules="ruleValidate"  :label-width="120">
            <FormItem label="用户头像">
              <Upload
                :before-upload="handleUpload"
                action="//jsonplaceholder.typicode.com/posts/"
                :format="['jpg','jpeg','png']"
              >
                <Button icon="ios-cloud-upload-outline">头像上传</Button>
              </Upload>
              <div v-if="showAvatar !== ''" class="avatar_wrap">
                <img :src="showAvatar"/>
              </div>
              <span v-show="!showAvatar" style="color: red">没有上传记录</span>
            </FormItem>
            <FormItem label="真实姓名" prop="nickname">
              <Input v-model="User_form.nickname"></Input>
            </FormItem>
            <FormItem label="用户名" prop="username">
              <Input disabled v-model="User_form.username"></Input>
            </FormItem>
            <FormItem label="密码">
              <Input type="password" v-model="User_form.password"></Input>
              <span style="color: red">（不修改可不填）</span>
            </FormItem>
            <FormItem label="手机号码" prop="phone">
              <Input v-model="User_form.phone"></Input>
            </FormItem>
            <FormItem label="电子邮箱" prop="email">
              <Input v-model="User_form.email"></Input>
            </FormItem>
            <FormItem label="性别">
              <RadioGroup v-model="User_form.sex">
                <Radio label='1'>男</Radio>
                <Radio label='2'>女</Radio>
              </RadioGroup>
            </FormItem>
            <FormItem label="微信号" >
              <Input v-model="User_form.weixin"></Input>
            </FormItem>
            <FormItem label="qq号码" >
              <Input v-model="User_form.qq"></Input>
            </FormItem>
            <FormItem>
              <Button type="primary"  @click="updateConfig">保存</Button>
            </FormItem>
          </Form>
        </Col>
      </Row>
    </Card>
  </div>
</template>
<script>
import { updateUser, updateUserAvatar } from '@/api/data'

export default {
  components: {
  },
  data () {
    return {
      User_form: {
        id: 0,
        username: '',
        avatar: null,
        password: '',
        nickname: '',
        phone: '',
        email: '',
        sex: '1',
        weixin: '',
        qq: ''
      },
      showAvatar: '',
      ruleValidate: {
      }
    }
  },
  methods: {
    initVform () {
      var v_arr = [
        { 'name': 'username', 'method': 'NotNull' },
        { 'name': 'name', 'method': 'NotNull' },
        { 'name': 'nickname', 'method': 'NotNull' },
        { 'name': 'phone', 'method': 'NotNull,NotMobile' },
        { 'name': 'email', 'method': 'NotNull,NotEmail' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      var data = this.$store.state.user.userDate
      if (data.avatar) {
        this.showAvatar = this.$constant.StaticUrl + data.avatar
      } else {}
      this.User_form.id = data.id
      this.User_form.username = data.username
      this.User_form.nickname = data.nickname
      this.User_form.phone = data.phone
      this.User_form.email = data.email
      this.User_form.sex = (data.sex).toString()
      this.User_form.weixin = data.weixin
      this.User_form.qq = data.qq
    },
    // 上传头像
    handleUpload (file) {
      this.User_form.avatar = file
      var reads = new FileReader()
      reads.readAsDataURL(file)
      var _this = this
      reads.onload = function (e) {
        _this.showAvatar = this.result
      }
      return false
    },
    updateConfig () {
      var id = this.User_form.id
      var password = this.User_form.password
      var nickname = this.User_form.nickname
      var phone = this.User_form.phone
      var email = this.User_form.email
      var sex = this.User_form.sex
      var weixin = this.User_form.weixin
      var qq = this.User_form.qq
      var avatar = this.User_form.avatar
      var data = {
        'id': id,
        'nickname': nickname,
        'phone': phone,
        'email': email,
        'sex': sex,
        'weixin': weixin,
        'qq': qq
      }
      if (password) {
        data['password'] = password
      } else {}
      this.$refs.User_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          updateUser(data).then(res => {
            if (res.data.msg === 'ok') {
              if (avatar) {
                updateUserAvatar(id, avatar).then(res => {
                  this.$Spin.hide()
                  if (res.data.msg === 'ok') {
                    this.$Message.success('成功修改')
                    window.location.reload()
                  } else { this.$Message.error(res.data.msg) }
                }).catch(ess => {
                  this.$Spin.hide()
                  this.$Message.error('请联系管理员')
                })
              } else {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                window.location.reload()
              }
            } else {
              this.$Spin.hide()
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else {
          return false
        }
      })
    }
  },
  mounted () {
    this.initVform()
  }
}
</script>

<style scoped>
  .avatar_wrap img{
    width: 120px;
    height: 120px;
    border-radius: 60px;
  }
</style>
