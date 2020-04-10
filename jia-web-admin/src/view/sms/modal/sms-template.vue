<template>
  <Modal
    :title="SmsTemplate_title"
    v-model="SmsTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="SmsTemplate_form" :model="SmsTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="模板名称 (必填)" prop="name">
            <Input v-model="SmsTemplate_form.name"></Input>
        </FormItem>
        <FormItem label="模板内容 (必填)" prop="content">
            <Input v-model="SmsTemplate_form.content"></Input>
        </FormItem>
          <FormItem label="类型">
            <RadioGroup v-model="SmsTemplate_form.type">
                <Radio label="1">短信验证码</Radio>
                <Radio label="2">待办任务</Radio>
            </RadioGroup>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="SmsTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      SmsTemplate_title: '短信模板',
      SmsTemplate_modal: false,
      SmsTemplate_Validate: '',
      SmsTemplate_judge: '',
      SmsTemplate_templateId: '',
      SmsTemplate_form: {
        name: '',
        content: '',
        type: '1'
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
        { 'name': 'content', 'method': 'NotNull' }
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

<style lang="less">

</style>
