<template>
  <Modal
    :title="sql_title"
    v-model="sql_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="sql_form" :model="sql_form" label-position="left" :rules="ruleValidate"  :label-width="180">
          <FormItem label="数据库限额 (必填)" prop="sqlQuota">
            <Input type="text" v-model="sql_form.sqlQuota"></Input>
        </FormItem>
            <!--<FormItem label="是否提供数据库服务 (必填)" prop="sqlService">-->
             <!--<Select v-model="sql_form.sqlService"  filterable>-->
                  <!--<Option v-for="(option, v) in SQLService" :value="option.id" :key="v">{{option.str}}</Option>-->
                <!--</Select>-->
        <!--</FormItem>-->
           <FormItem label="数据库密码 (必填)" prop="sqlPasswd">
            <Input type="password" v-model="sql_form.sqlPasswd"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="sql_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>

export default {
  data () {
    return {
      sql_title: 'SQL信息',
      sql_modal: false,
      sql_Validate: '',
      sql_Id: '',
      sql_form: {
        sqlPasswd: '',
        sqlQuota: ''
        // sqlService: ''
      },
      // SQLService: [],
      ruleValidate: {
      }
    }
  },
  methods: {
    next () {
      this.$emit('sqlOperate', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'sqlPasswd', 'method': 'NotNull' },
        { 'name': 'sqlQuota', 'method': 'NotNull,NotInt' }
        // { 'name': 'sqlService', 'method': 'NotNull,NotInt' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      // this.SQLService = this.$constant.sqlService
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less">

</style>
