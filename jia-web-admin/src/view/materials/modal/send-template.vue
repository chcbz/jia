<template>
  <Modal
    :title="SendTemplate_title"
    v-model="SendTemplate_modal"
    :closable="false"
    :mask-closable="false">
    <span>
        <Form ref="SendTemplate_form" :model="SendTemplate_form" label-position="left" :rules="ruleValidate"  :label-width="120">
        <FormItem label="用户UID" prop="jiacn">
          <Select v-model="SendTemplate_form.jiacn" filterable>
                  <Option v-for="(option, v) in user_params" :value="option.uid"  :arr="JSON.stringify(option)" :key="v" :ref="'uid_'+option.uid">{{option.nickname}}</Option>
                </Select>
        </FormItem>
          <FormItem label="类型">
            <RadioGroup v-model="SendTemplate_form.type">
                <Radio :label='2'>邮箱</Radio>
                 <Radio :label='3'>短信</Radio>
               <Radio :label='1'>微信</Radio>
            </RadioGroup>
        </FormItem>
           <FormItem label="公众号" prop="appid"  v-show="SendTemplate_form.type==1" :rules="{validator: newNotNull,  trigger: 'blur'}">
          <Select v-model="SendTemplate_form.appid"  filterable>
                  <Option v-for="(option, v) in wxMp_params" :value="option.appid" :key="v">{{option.name}}</Option>
                </Select>
        </FormItem>
    </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="SendTemplate_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
import { LdapUserAll, WxMpList, sendNews } from '@/api/data'
export default {
  data () {
    return {
      SendTemplate_title: '发送图文',
      SendTemplate_modal: false,
      SendTemplate_Validate: '',
      SendTemplate_templateId: '',
      user_params: [],
      wxMp_params: [],
      SendTemplate_form: {
        jiacn: '',
        type: 2,
        appid: ''
      },
      ruleValidate: {
      }
    }
  },
  methods: {
    newNotNull (rule, y, callback) {
      var type = this.SendTemplate_form.type
      if (y === '' && type === 1) {
        return callback(new Error('该项为必填项'))
      } else {
        callback()
      }
    },
    // LDAP所有用户
    LdapUserAll () {
      LdapUserAll().then(res => {
        var arr = res.data.data
        this.user_params = arr
      })
    },
    // 公众号列表
    getWxMpList () {
      const pageNum = 1
      const pageSize = 999
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      var Narr = []
      WxMpList(data).then(res => {
        var arr = res.data.data
        arr.forEach(function (val, i) {
          Narr.push(val)
        })
        this.wxMp_params = Narr
      })
    },
    next () {
      var id = this.SendTemplate_templateId
      var jiacn = this.SendTemplate_form.jiacn
      var type = this.SendTemplate_form.type
      var wxAppId = this.SendTemplate_form.appid
      var data = {
        'id': id,
        'jiacn': jiacn,
        'type': type
      }
      this.$refs.SendTemplate_form.validate((valid) => {
        if (valid) {
          var jiaC = this.$refs['uid_' + jiacn]
          var jiaArr = JSON.parse(jiaC[0].$attrs.arr)
          var email = jiaArr.email
          var telephoneNumber = jiaArr.telephoneNumber
          var openid = jiaArr.openid
          if (type === 2 && !email) {
            this.$Message.error('该用户邮箱未绑定')
          } else if (type === 3 && !telephoneNumber) {
            this.$Message.error('该用户手机未绑定')
          } else if (type === 1 && !openid) {
            this.$Message.error('该用户微信未绑定')
          } else {
            if (type === 1) { data['wxappid'] = wxAppId } else {}
            this.$Spin.show()
            sendNews(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$Spin.hide()
                this.SendTemplate_modal = false
                window.location.reload()
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          }
        } else {
          return false
        }
      })
    },
    initVform () {
      var v_arr = [
        { 'name': 'jiacn', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      this.LdapUserAll()
      this.getWxMpList()
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less">

</style>
