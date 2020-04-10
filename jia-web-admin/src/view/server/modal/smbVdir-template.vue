<template>
  <Modal
    :title="smbVdir_title"
    v-model="smbVdir_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="smbVdir_form" :model="smbVdir_form" label-position="left" :rules="ruleValidate"  :label-width="150">
           <FormItem label="账户UID (必填)" prop="user">
             <Select v-model="smbVdir_form.user" filterable :disabled="smbVdir_judge==='update'">
                  <Option v-for="(option, v) in user_params" :value="option.uid" :key="v">{{option.uid+'/'+option.cn}}</Option>
                </Select>
        </FormItem>
           <FormItem label="虚拟目录名称 (必填)" prop="name">
            <Input type="text" v-model="smbVdir_form.name" :disabled="smbVdir_judge==='update'"></Input>
        </FormItem>
           <FormItem label="物理路径 (必填)" prop="path">
            <Input type="text" v-model="smbVdir_form.path"></Input>
        </FormItem>
           <FormItem label="是否有效">
            <RadioGroup v-model="smbVdir_form.available">
                <Radio label='yes'>yes</Radio>
                <Radio label='no'>no</Radio>
            </RadioGroup>
        </FormItem>
           <FormItem label="是否可写">
            <RadioGroup v-model="smbVdir_form.writable">
                <Radio label='yes'>yes</Radio>
                <Radio label='no'>no</Radio>
            </RadioGroup>
        </FormItem>
           <FormItem label="是否可浏览">
            <RadioGroup v-model="smbVdir_form.browseable">
                <Radio label='yes'>yes</Radio>
                <Radio label='no'>no</Radio>
            </RadioGroup>
        </FormItem>
           <FormItem label="是否可打印">
            <RadioGroup v-model="smbVdir_form.printable">
                <Radio label='yes'>yes</Radio>
                <Radio label='no'>no</Radio>
            </RadioGroup>
        </FormItem>
           <FormItem label="描述">
              <Input type="textarea" v-model="smbVdir_form.comment"></Input>
            </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="smbVdir_modal=false">取消</Button>
      <Button type="primary" size="large" @click="smbVdirNext">确定</Button>
    </div>
  </Modal>
</template>

<script>
import { LdapAccountList } from '@/api/data'
export default {
  props: {
    serverId: {
      type: Number
    }
  },
  data () {
    return {
      smbVdir_title: 'smbVdir服务',
      smbVdir_modal: false,
      smbVdir_Validate: '',
      smbVdir_judge: '',
      smbVdir_Id: '',
      user_params: [],
      smbVdir_form: {
        name: '',
        user: '',
        path: '',
        available: 'yes',
        writable: 'yes',
        browseable: 'yes',
        printable: 'no',
        comment: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    // LDAP系统账户列表
    LdapAccountList () {
      const serverId = this.serverId
      const pageSize = 9999
      var data = {
        'pageSize': pageSize,
        'search': {
          'serverId': serverId
        }
      }
      LdapAccountList(data).then(res => {
        var arr = res.data.data
        this.user_params = arr
      })
    },
    smbVdirNext () {
      this.$emit('smbVdirNext', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'name', 'method': 'NotNull' },
        { 'name': 'user', 'method': 'NotNull' },
        { 'name': 'path', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      this.LdapAccountList()
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less">

</style>
