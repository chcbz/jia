<template>
  <Modal
    :title="LDAPGroup_title"
    v-model="LDAPGroup_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="LDAPGroup_form" :model="LDAPGroup_form" label-position="left" :rules="ruleValidate"  :label-width="120">
          <FormItem label="账户组CN (必填)" prop="cn">
            <Input type="text" v-model="LDAPGroup_form.cn" :disabled="LDAPGroup_judge=='update'"></Input>
        </FormItem>
           <FormItem label="描述">
            <Input type="text" v-model="LDAPGroup_form.description"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="LDAPGroup_modal=false">取消</Button>
      <Button type="primary" size="large" @click="LDAPGroupNext">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      LDAPGroup_title: 'LDAPGroup服务',
      LDAPGroup_modal: false,
      LDAPGroup_Validate: '',
      LDAPGroup_judge: '',
      LDAPGroup_Id: '',
      LDAPGroup_params: [],
      LDAPGroup_form: {
        cn: '',
        description: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    LDAPGroupNext () {
      this.$emit('LDAPGroupNext', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'cn', 'method': 'NotNull' }
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
