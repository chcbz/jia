<template>
  <Modal
    :title="Server_title"
    v-model="Server_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="Server_form" :model="Server_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="服务器名 (必填)" prop="serverName">
            <Input type="text" v-model="Server_form.serverName"></Input>
        </FormItem>
        <FormItem label="服务器描述">
            <Input type="text" v-model="Server_form.serverDescription"></Input>
        </FormItem>
      <FormItem label="ip (必填)" prop="ip">
            <Input type="text" v-model="Server_form.ip"></Input>
        </FormItem>
          <FormItem label="SSH端口 (必填)" prop="sshPort">
            <Input type="text" v-model="Server_form.sshPort"></Input>
        </FormItem>
           <FormItem label="SSH用户名 (必填)" prop="sshUser">
            <Input type="text" v-model="Server_form.sshUser"></Input>
        </FormItem>
           <FormItem label="SSH密码 (必填)" prop="sshPassword">
            <Input type="password" v-model="Server_form.sshPassword"></Input>
        </FormItem>
           <FormItem label="控制台端口 (必填)" prop="consolePort">
            <Input type="text" v-model="Server_form.consolePort"></Input>
        </FormItem>
            <FormItem label="控制台密码 (必填)" prop="consoleToken">
            <Input type="password" v-model="Server_form.consoleToken"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="Server_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      Server_title: '服务器信息',
      Server_modal: false,
      Server_Validate: '',
      Server_judge: '',
      Server_Id: '',
      Server_form: {
        serverName: '',
        serverDescription: '',
        ip: '',
        sshPort: '',
        sshUser: '',
        sshPassword: '',
        consolePort: '',
        consoleToken: ''
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
        { 'name': 'serverName', 'method': 'NotNull' },
        { 'name': 'ip', 'method': 'NotNull' },
        { 'name': 'sshPort', 'method': 'NotNull,NotInt' },
        { 'name': 'sshUser', 'method': 'NotNull' },
        { 'name': 'sshPassword', 'method': 'NotNull' },
        { 'name': 'consolePort', 'method': 'NotNull' },
        { 'name': 'consoleToken', 'method': 'NotNull' }
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
