<template>
  <div>
    <Card>
      <Row>
        <Col offset="1" :sm="14" :md="14" :lg="8">
          <Form ref="oauthClient_form" :model="oauthClient_form" label-position="left" :rules="ruleValidate" :label-width="150">
            <FormItem label="应用标识码" prop="appcn">
              <Input  v-model="oauthClient_form.appcn"></Input>
            </FormItem>
            <FormItem label="客户端ID">
              <Input disabled v-model="oauthClient_form.client_id"></Input>
            </FormItem>
            <FormItem label="客户端密码">
              <Input type="password"  v-model="oauthClient_form.client_secret"></Input>
              <span style="color: red">（不修改可不填）</span>
            </FormItem>
            <FormItem label="token有效期">
              <Input disabled v-model="oauthClient_form.access_token_validity"></Input>
            </FormItem>
            <FormItem label="token刷新有效期">
              <Input disabled  v-model="oauthClient_form.refresh_token_validity"></Input>
            </FormItem>
            <FormItem>
              <Button type="primary"  @click="updateConfig">修改</Button>
            </FormItem>
          </Form>
        </Col>
      </Row>
    </Card>
  </div>
</template>
<script>
import { getOauthClient, updateOauthClient } from '@/api/data'

export default {
  components: {

  },
  data () {
    return {
      oauthClient_form: {
        appcn: '1',
        client_id: '',
        client_secret: '',
        access_token_validity: '',
        refresh_token_validity: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    initVform () {
      this.getConfig()
      var v_arr = [
        { 'name': 'appcn', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    },
    getConfig () {
      getOauthClient().then(res => {
        var data = res.data.data
        this.oauthClient_form.appcn = data.appcn
        this.oauthClient_form.client_id = data.client_id
        // this.oauthClient_form.client_secret = data.client_secret
        this.oauthClient_form.access_token_validity = data.access_token_validity
        this.oauthClient_form.refresh_token_validity = data.refresh_token_validity
      })
    },
    updateConfig () {
      var appcn = this.oauthClient_form.appcn
      var client_secret = this.oauthClient_form.client_secret
      var data
      if (client_secret) {
        data = {
          'appcn': appcn,
          'client_secret': client_secret
        }
      } else {
        data = {
          'appcn': appcn
        }
      }
      this.$refs.oauthClient_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          updateOauthClient(data).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('成功修改')
            } else { this.$Message.error(res.data.msg) }
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
