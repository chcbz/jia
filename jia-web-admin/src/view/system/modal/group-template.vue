<template>
  <Modal
    :title="GroupTemplate_title"
    v-model="GroupTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="GroupTemplate_form" :model="GroupTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="名称 (必填)" prop="name">
            <Input v-model="GroupTemplate_form.name"></Input>
        </FormItem>
        <FormItem label="编码 (必填)" prop="code">
            <Input v-model="GroupTemplate_form.code"></Input>
        </FormItem>
          <FormItem label="备注">
           <Input v-model="GroupTemplate_form.remark" type="textarea"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="GroupTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      GroupTemplate_title: '用户组模板',
      GroupTemplate_modal: false,
      GroupTemplate_Validate: '',
      GroupTemplate_judge: '',
      GroupTemplate_templateId: '',
      GroupTemplate_form: {
        name: '',
        code: '',
        remark: ''
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
        { 'name': 'code', 'method': 'NotNull' }
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
