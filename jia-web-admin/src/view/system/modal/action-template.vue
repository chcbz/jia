<template>
  <Modal
    :title="Action_title"
    v-model="Action_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="Action_form" :model="Action_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="描述">
            <Input v-model="Action_form.description"></Input>
        </FormItem>
        <FormItem label="状态"  prop="level">
            <RadioGroup v-model="Action_form.level">
                <Radio :label='1'>总平台</Radio>
                <Radio :label='2'>企业平台</Radio>
            </RadioGroup>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="Action_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      Action_title: '权限',
      Action_modal: false,
      Action_Validate: '',
      Action_judge: '',
      Action_templateId: '',
      Action_form: {
        level: 0,
        description: ''
      },
      parentIdArr: [],
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
        { 'name': 'description', 'method': 'NotNull' }
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

<style lang="less" scoped>
</style>
