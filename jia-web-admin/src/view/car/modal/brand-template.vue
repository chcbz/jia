<template>
  <Modal
    :title="brandTemplate_title"
    v-model="brandTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="brandTemplate_form" :model="brandTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="品牌名称 (必填)" prop="brand">
            <Input v-model="brandTemplate_form.brand"></Input>
        </FormItem>
        <FormItem label="缩写 (必填)" prop="abbr">
            <Input v-model="brandTemplate_form.abbr"></Input>
        </FormItem>
          <FormItem label="首字母 (必填)" prop="initials">
            <Input v-model="brandTemplate_form.initials"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="brandTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      brandTemplate_title: '',
      brandTemplate_modal: false,
      brandTemplate_Validate: '',
      brandTemplate_judge: '',
      brandTemplate_id: '',
      brandTemplate_form: {
        brand: '',
        abbr: '',
        initials: ''
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
        { 'name': 'brand', 'method': 'NotNull' },
        { 'name': 'abbr', 'method': 'NotNull' },
        { 'name': 'initials', 'method': 'NotNull' }
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
