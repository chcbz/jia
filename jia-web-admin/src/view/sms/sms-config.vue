<template>
  <div>
    <Card>
      <Row>
        <Col offset="1" :sm="14" :md="14" :lg="8">
          <Form ref="sms_form" :model="sms_form" label-position="left" :rules="ruleValidate" :label-width="150">
            <FormItem label="简称" prop="shortName">
              <Input v-model="sms_form.shortName"></Input>
            </FormItem>
            <FormItem label="短信回复回调地址">
              <Input v-model="sms_form.replyUrl"></Input>
            </FormItem>
            <FormItem label="剩余短息数量">
              <Input disabled  v-model="sms_form.remain"></Input>
            </FormItem>
            <FormItem>
              <Button type="primary"  @click="updateConfig">保存</Button>
              <!--<Button type="warning" style="margin-left: 2px" @click="goBuy">购买</Button>-->
            </FormItem>
          </Form>
        </Col>
      </Row>
    </Card>
  </div>
</template>
<script>
import { registerSmsConfig, getSmsConfig, updateSmsConfig } from '@/api/data'

export default {
  name: 'sms_config',
  components: {

  },
  data () {
    return {
      sms_form: {
        shortName: '',
        replyUrl: '',
        clientId: '',
        remain: 0
      },
      ruleValidate: {}
    }
  },
  methods: {
    // goBuy () {
    //   this.$router.push({ name: 'sms_package' })
    // },
    initVform () {
      var v_arr = [
        { 'name': 'shortName', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    },
    getConfig () {
      getSmsConfig().then(res => {
        this.$refs.sms_form.resetFields()
        if (res.data.msg === 'ok') {
          var hasData = res.data.hasOwnProperty('data')
          if (hasData) {
            this.sms_form.clientId = res.data.data.clientId
            this.sms_form.shortName = res.data.data.shortName
            this.sms_form.replyUrl = res.data.data.replyUrl
            this.sms_form.remain = res.data.data.remain
          } else {}
        } else {}
      })
    },
    updateConfig () {
      var shortName = this.sms_form.shortName
      var replyUrl = this.sms_form.replyUrl
      var clientId = this.sms_form.clientId
      console.log(clientId)
      var data = {
        'shortName': shortName,
        'replyUrl': replyUrl
      }
      this.$refs.sms_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          if (clientId) {
            // 更新
            updateSmsConfig(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功上传')
                window.location.reload()
              } else {
                this.$Spin.hide()
                this.$Message.error(res.data.msg)
              }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            // 注册
            registerSmsConfig(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功上传')
                window.location.reload()
              } else {
                this.$Spin.hide()
                this.$Message.error(res.data.msg)
              }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          }
        } else {
          return false
        }
      })
    }
  },
  mounted () {
    this.initVform()
    this.getConfig()
  }
}
</script>
