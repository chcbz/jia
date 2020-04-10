<template>
  <Modal
    :title="readyReason_title"
    v-model="readyReason_modal"
    :closable="false"
    :mask-closable="false">
      <span>
        <Form ref="readyReason_form" :model="readyReason_form" label-position="top" :rules="ruleValidate">
          <FormItem label="删除原因" prop="deleteReason">
           <Input v-model="readyReason_form.deleteReason" type="textarea" :rows="4"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="readyReason_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      readyReason_title: '删除',
      readyReason_modal: false,
      readyReason_content: '',
      readyReason_params: [],
      readyReason_judge: '',
      readyReason_form: {
        deleteReason: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    initVform () {
      var v_arr = [
        { 'name': 'deleteReason', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    },
    next () {
      this.$emit('NextReason', '')
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less">

</style>
