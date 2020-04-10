<template>
  <div>
    <Card>
      <Row>
        <Col offset="1" :sm="14" :md="14" :lg="8">
          <Form ref="sendBatch_form" :model="sendBatch_form" label-position="left" :label-width="150">
            <div  v-for="(item,index) in sendBatch_form.mobileArr"  :key="index" class="mobileArr_wrap">
              <FormItem label="手机号码" :rules="{validator: mobileCheck, trigger: 'blur'}"  :prop="'mobileArr.' + index + '.mobile'">
                <Input v-model="item.mobile"></Input>
              </FormItem>
              <div  class="s_icon_div mobile_remove">
                <li>
                  <Icon type="ios-remove-circle" size="30" @click="removeMobile(index)"/>
                  <Icon type="ios-add-circle" size="30" @click="addMobile"/>
                </li>
              </div>
            </div>
            <FormItem label="短信内容">
              <Input type="textarea" v-model="sendBatch_form.content"></Input>
            </FormItem>
            <FormItem>
              <Button type="primary"  @click="sendBatch">发送</Button>
            </FormItem>
          </Form>
        </Col>
      </Row>
    </Card>
  </div>
</template>
<script>
import { smsSendBatch } from '@/api/data'

export default {
  components: {

  },
  data () {
    return {
      sendBatch_form: {
        mobileArr: [{
          index: 0,
          mobile: ''
        }],
        content: ''
      },
      ruleValidate: {}
    }
  },
  methods: {
    mobileCheck (rule, y, callback) {
      var mobile = y
      var reg = /^1[34578][0-9]{9}$/ // 验证规则
      if (mobile === '') {
        return callback(new Error('该项为必填项'))
      } else if (!mobile) { callback() } else if (reg.test(mobile) === false) {
        return callback(new Error('手机号码不符合规范'))
      } else {
        callback()
      }
    },
    addMobile () {
      this.index++
      this.sendBatch_form.mobileArr.push(
        {
          index: this.index,
          mobile: ''
        }
      )
    },
    removeMobile (i) {
      if (i === 0) {
        this.$Message.error('该列不能删除')
      } else {
        this.sendBatch_form.mobileArr.splice(i, 1)
      }
    },
    sendBatch () {
      var mobileArr = this.sendBatch_form.mobileArr
      var mobile = ''
      mobileArr.forEach((val, i) => {
        var value = val.mobile
        mobile = mobile + value + ','
      })
      mobile = this.$constant.removeLastChar(mobile)
      var content = this.sendBatch_form.content
      var data = {
        'mobile': mobile,
        'content': content
      }
      this.$refs.sendBatch_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          smsSendBatch(data).then(res => {
            if (res.data.msg === 'ok') {
              this.$Spin.hide()
              this.$Message.success('成功发送')
              window.location.reload()
            } else {
              this.$Spin.hide()
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else {
          return false
        }
      })
    }
  },
  mounted () {
  }
}
</script>

<style lang="less" scoped>
  .s_icon_div{
    margin: 10px auto;
  }
  .mobileArr_wrap{
    position: relative;
  }
  .mobile_remove{
    position: absolute;
    top: -2px;
    margin-top: 0;
    right: -75px;
  }
</style>
