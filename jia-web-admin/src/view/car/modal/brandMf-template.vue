<template>
  <Modal
    :title="brandMfTemplate_title"
    v-model="brandMfTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="brandMfTemplate_form" :model="brandMfTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="150">
          <FormItem label="品牌" prop="brand">
            <Select v-model="brandMfTemplate_form.brand" filterable :disabled="brandMfTemplate_disabled">
                  <Option v-for="(option, v) in brandMfTemplate_brandArr" :value="option.id" :key="v">{{option.value}}</Option>
            </Select>
        </FormItem>
        <FormItem label="制造商名称 (必填)" prop="brand">
            <Input v-model="brandMfTemplate_form.brandMf"></Input>
        </FormItem>
        <FormItem label="缩写 (必填)" prop="abbr">
            <Input v-model="brandMfTemplate_form.abbr"></Input>
        </FormItem>
          <FormItem label="首字母 (必填)" prop="initials">
            <Input v-model="brandMfTemplate_form.initials"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="brandMfTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      brandMfTemplate_title: '',
      brandMfTemplate_brandArr: '',
      brandMfTemplate_modal: false,
      brandMfTemplate_Validate: '',
      brandMfTemplate_judge: '',
      brandMfTemplate_disabled: false,
      brandMfTemplate_id: '',
      brandMfTemplate_form: {
        brand: '',
        brandMf: '',
        abbr: '',
        initials: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    // 初始化页面
    initVue () {
      this.initVform()
      this.$constant.appendBrand(this, 'brandMfTemplate_brandArr')
    },
    next () {
      this.$emit('operate', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'brandMf', 'method': 'NotNull' },
        { 'name': 'abbr', 'method': 'NotNull' },
        { 'name': 'initials', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    }
  },
  created () {
    this.initVue()
  }
}
</script>

<style lang="less">

</style>
