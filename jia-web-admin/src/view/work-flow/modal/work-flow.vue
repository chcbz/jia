<template>
  <Modal
    :title="work_title"
    v-model="work_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="work_form" :model="work_form" label-position="left" :rules="ruleValidate"  :label-width="100">
        <FormItem label="部署名称 (必填)" prop="name">
            <Input v-model="work_form.name"></Input>
        </FormItem>
        <FormItem label="部署类别" prop="category">
            <Input v-model="work_form.category"></Input>
        </FormItem>
        <FormItem label="关键词">
            <Input v-model="work_form.key"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="work_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      work_title: '工作流部署',
      work_modal: false,
      work_Validate: '',
      work_form: {
        name: '',
        category: '',
        key: ''
      },
      ruleValidate: {
        // name: [{ validator: this.$constant.verifyArr.NotNull, trigger: 'blur' }]
      }
    }
  },
  methods: {
    next () {
      this.$emit('Next', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'name', 'method': 'NotNull' }
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
