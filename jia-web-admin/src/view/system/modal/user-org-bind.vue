<template>
  <Modal
    :title="UserOrgBind_title"
    v-model="UserOrgBind_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="UserOrgBind_form" :model="UserOrgBind_form" label-position="left" :rules="ruleValidate"  :label-width="120">
          <FormItem label="手机号码" prop="phone">
            <Input v-model="UserOrgBind_form.phone"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="UserOrgBind_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      UserOrgBind_title: '用户绑定',
      UserOrgBind_modal: false,
      UserOrgBind_form: {
        phone: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    next () {
      this.$emit('bind', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'phone', 'method': 'NotNull,NotMobile' }
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
