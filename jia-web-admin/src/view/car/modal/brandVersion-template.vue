<template>
  <Modal
    :title="brandVersionTemplate_title"
    v-model="brandVersionTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="brandVersionTemplate_form" :model="brandVersionTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="150">
          <FormItem label="系列" prop="audi">
            <Select v-model="brandVersionTemplate_form.audi" filterable :disabled="brandVersionTemplate_disabled">
                  <Option v-for="(option, v) in brandVersionTemplate_audiArr" :value="option.id" :key="v">{{option.value}}</Option>
            </Select>
        </FormItem>
        <FormItem label="车型规格 (必填)" prop="version">
            <Input v-model="brandVersionTemplate_form.version"></Input>
        </FormItem>
        <FormItem label="车型名称 (必填)" prop="vName">
            <Input v-model="brandVersionTemplate_form.vName"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="brandVersionTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      brandVersionTemplate_title: '',
      brandVersionTemplate_audiArr: '',
      brandVersionTemplate_modal: false,
      brandVersionTemplate_Validate: '',
      brandVersionTemplate_judge: '',
      brandVersionTemplate_disabled: false,
      brandVersionTemplate_id: '',
      brandVersionTemplate_form: {
        audi: '',
        version: '',
        vName: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    // 初始化页面
    initVue () {
      this.initVform()
      this.$constant.appendBrandAudi(this, 'brandVersionTemplate_audiArr')
    },
    next () {
      this.$emit('operate', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'audi', 'method': 'NotNull' },
        { 'name': 'version', 'method': 'NotNull' },
        { 'name': 'vName', 'method': 'NotNull' }
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
