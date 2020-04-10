<template>
  <Modal
    :title="DictTemplate_title"
    v-model="DictTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="DictTemplate_form" :model="DictTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="名称 (必填)" prop="name">
            <Input v-model="DictTemplate_form.name"></Input>
        </FormItem>
           <FormItem label="内容 (必填)" prop="value">
            <Input v-model="DictTemplate_form.value"></Input>
        </FormItem>
        <FormItem label="序列值 (必填)" prop="dictOrder">
            <Input v-model="DictTemplate_form.dictOrder"></Input>
        </FormItem>
          <FormItem label="列类型">
          <Select v-model="DictTemplate_form.parentId" clearable filterable>
              <Option v-for="(option, i) in parentIdArr" :value="option.id" :key="i">{{option.name}}</Option>
          </Select>
        </FormItem>
        <FormItem label="状态">
            <RadioGroup v-model="DictTemplate_form.status">
                <Radio label='1'>有效</Radio>
                <Radio label='2'>失效</Radio>
            </RadioGroup>
        </FormItem>
          <FormItem label="备注">
           <Input v-model="DictTemplate_form.description" type="textarea"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="DictTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
import { getDictTable } from '@/api/data'
export default {
  data () {
    return {
      DictTemplate_title: '短信模板',
      DictTemplate_modal: false,
      DictTemplate_Validate: '',
      DictTemplate_judge: '',
      DictTemplate_templateId: '',
      DictTemplate_form: {
        name: '',
        value: '',
        dictOrder: '',
        parentId: '',
        status: '1',
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
        { 'name': 'name', 'method': 'NotNull' },
        { 'name': 'value', 'method': 'NotNull' },
        { 'name': 'dictOrder', 'method': 'NotNull,NotInt' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    },
    // 获取父id列表
    appendParentId () {
      const pageNum = 1
      const pageSize = 999
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      getDictTable(data).then(res => {
        this.parentIdArr = res.data.data
      })
    }
  },
  created () {
    this.initVform()
    this.appendParentId()
  }
}
</script>

<style lang="less" scoped>
/deep/.ivu-modal{
  width: 550px!important;
}
</style>
