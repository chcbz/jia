<template>
  <Modal
    :title="brandAudiTemplate_title"
    v-model="brandAudiTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="brandAudiTemplate_form" :model="brandAudiTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="150">
          <FormItem label="品牌" prop="brand">
            <Select v-model="brandAudiTemplate_form.brandMf" filterable :disabled="brandAudiTemplate_disabled">
                  <Option v-for="(option, v) in brandAudiTemplate_brandMfArr" :value="option.id" :key="v">{{option.value}}</Option>
            </Select>
        </FormItem>
        <FormItem label="制造商名称 (必填)" prop="brand">
            <Input v-model="brandAudiTemplate_form.carSeries"></Input>
        </FormItem>
        <FormItem label="缩写 (必填)" prop="abbr">
            <Input v-model="brandAudiTemplate_form.abbr"></Input>
        </FormItem>
          <FormItem label="首字母 (必填)" prop="initials">
            <Input v-model="brandAudiTemplate_form.initials"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="brandAudiTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      brandAudiTemplate_title: '',
      brandAudiTemplate_brandMfArr: '',
      brandAudiTemplate_modal: false,
      brandAudiTemplate_Validate: '',
      brandAudiTemplate_judge: '',
      brandAudiTemplate_disabled: false,
      brandAudiTemplate_id: '',
      brandAudiTemplate_form: {
        carSeries: '',
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
      this.$constant.appendBrandMf(this, 'brandAudiTemplate_brandMfArr')
    },
    next () {
      this.$emit('operate', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'carSeries', 'method': 'NotNull' },
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
