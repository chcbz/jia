<template>
  <Modal
    :title="domain_title"
    v-model="domain_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="domain_form" :model="domain_form" label-position="left" :rules="ruleValidate"  :label-width="140">
        <FormItem label="域名 (必填)" prop="domainName">
            <Input type="text" v-model="domain_form.domainName"></Input>
        </FormItem>
            <FormItem label="所属服务器ID (必填)" prop="serverId">
             <Select v-model="domain_form.serverId"  filterable>
                  <Option v-for="(option, v) in serverId" :value="option.id" :key="v">{{option.str}}</Option>
                </Select>
        </FormItem>
           <FormItem label="DNS类型 (必填)" prop="dnsType">
             <Select v-model="domain_form.dnsType"  filterable>
                  <Option v-for="(option, v) in DnsType" :value="option.id" :key="v">{{option.str}}</Option>
                </Select>
        </FormItem>
      <FormItem label="DNS服务器密钥 (必填)" prop="dnsKey">
            <Input type="text" v-model="domain_form.dnsKey"></Input>
        </FormItem>
          <FormItem label="DNS服务器令牌 (必填)" prop="dnsToken">
            <Input type="text" v-model="domain_form.dnsToken"></Input>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="domain_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
import { ServerList } from '@/api/data'

export default {
  data () {
    return {
      domain_title: '服务器信息',
      domain_modal: false,
      domain_Validate: '',
      domain_judge: '',
      domain_Id: '',
      domain_form: {
        domainName: '',
        dnsType: 'aly',
        dnsKey: '',
        dnsToken: '',
        serverId: 0
      },
      DnsType: [],
      serverId: [],
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
        { 'name': 'domainName', 'method': 'NotNull' },
        { 'name': 'dnsType', 'method': 'NotNull' },
        { 'name': 'serverId', 'method': 'NotNull' },
        { 'name': 'dnsKey', 'method': 'NotNull' },
        { 'name': 'dnsToken', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      this.DnsType = this.$constant.DnsType
      const pageNum = 1
      const pageSize = 999
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      ServerList(data).then(res => {
        var result = res.data.data
        var arr = []
        result.forEach((val, i) => {
          var id = val.id
          var str = val.serverName
          var obj = { id: id, str: str }
          arr.push(obj)
        })
        this.serverId = arr
      })
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less">

</style>
