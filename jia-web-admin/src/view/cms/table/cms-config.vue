<template>
  <div>
    <Card>
      <Row>
        <Col offset="1" :sm="14" :md="14" :lg="8">
          <Form ref="cms_form" :model="cms_form" label-position="left" :rules="ruleValidate" :label-width="150" >
            <FormItem label="表格前缀" prop="tablePrefix_4">
              <Input v-model="cms_form.tablePrefix_4"></Input>
            </FormItem>
            <FormItem>
              <Button type="primary" :disabled="!!cms_form.clientId" @click="updateConfig">保存</Button>
            </FormItem>
          </Form>
        </Col>
      </Row>
    </Card>
  </div>
</template>
<script>
import { getCmsConfig, registerCmsConfig } from '@/api/data'

export default {
  components: {

  },
  data () {
    return {
      cms_form: {
        tablePrefix_4: '',
        clientId: ''
      },
      ruleValidate: {}
    }
  },
  methods: {
    initVform () {
      var v_arr = [
        { 'name': 'tablePrefix_4', 'method': 'NotNull,NotLength' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    },
    getConfig () {
      getCmsConfig().then(res => {
        this.$refs.cms_form.resetFields()
        if (res.data.msg === 'ok') {
          var hasData = res.data.hasOwnProperty('data')
          if (hasData) {
            this.cms_form.clientId = res.data.data.clientId
            this.cms_form.tablePrefix_4 = res.data.data.tablePrefix
          } else {}
        } else {}
      })
    },
    updateConfig () {
      this.$refs.cms_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          var tablePrefix = this.cms_form.tablePrefix_4
          var data = {
            'tablePrefix': tablePrefix
          }
          this.$Spin.show()
          registerCmsConfig(data).then(res => {
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
