<template>
  <div>
    <Card>
      <Row>
        <Col span="4" class="smsLogo_div"><img  :src="smsImage"  class="smsLogo"/></Col>
        <Col span="20" class="sms_title_div">
          <div class="sms_title">
            <h1>{{smsTitle}}</h1>
            <p>{{smsDesccription}}</p>
            <ul class="sms_trade">
                <li class="trade-amount">
                <i class="symbol">¥</i>
                <i class="sms_price">{{smsPackage_choicePrice}}</i>
                </li>
                <li class="sms_rate">
                    <span>用户评分：</span>
                    <Rate disabled v-model="smsRate" />
                </li>
            </ul>
          </div>
        </Col>
      </Row>
      <Row>
        <Col span="20" offset="4" class="sms_contnet_div">
          <ul class="sms_contnet">
            <li>
              <span class="sms_label">套餐版本：</span>
              <span>
               <Tag type="border" v-for="(option, i) in smsPackage_arr" @click.native="choiceSmsPackage(option.id)" :color="(option.id===smsPackage_choice)?'primary':'default'" :key="i">{{option.money}}元/{{option.number}}次</Tag>
            </span>
            </li>
            <li>
              <span class="sms_label">套餐配额：</span>
              <span>
               <Tag type="border" color="primary">{{smsPackage_choiceNumber}}次</Tag>
            </span>
            </li>
            <li style="margin-top: 10px">
              <Button type="success" @click="smsBuyCreate()">立即购买</Button>
            </li>
          </ul>
        </Col>
      </Row>
    </Card>
  </div>
</template>

<script>
import smsImage from '@/assets/images/smsLogo.jpg'
import { SmsPackageList, SmsBuyCreate } from '@/api/data'

export default {
  components: {},
  data () {
    return {
      smsImage,
      smsTitle: '【支持106三网营销短信】营销短信/短信群发/短信平台/70字推广短信/营销短信推广——短信API接口对接',
      smsDesccription: '支持106三网营销短信发送，提供会员营销短信群发服务，支持70个字的营销短信推广、短信群发、支持API营销短信接口，新产品发布短信推广、打折商品促销短信、\n' +
      '              电商推广群发短信、物流快递短信、品牌推广等网站/APP/商城推广群发短信与一般营销短信群发等。',
      smsRate: 5,
      smsPackage_choicePrice: 200,
      smsPackage_arr: [],
      smsPackage_choice: 1,
      smsPackage_choiceNumber: 0
    }
  },
  methods: {
    // 购买套餐
    smsBuyCreate () {
      const packageId = this.smsPackage_choice
      const data = {
        'packageId': packageId
      }
      this.$Spin.show()
      SmsBuyCreate(data).then(res => {
        if (res.data.msg === 'ok') {
          this.$Spin.hide()
          this.$Message.success('创建订单成功')
          var buyId = res.data.data.buyId
          var productId = res.data.data.productId
          this.$router.push({ path: '/wechat/wechat_scanPay/' + productId, query: { productId: productId, buyId: buyId } })
        } else {
          this.$Spin.hide()
          this.$Message.error(res.data.msg)
        }
      })
    },
    // 选择套餐
    choiceSmsPackage (id) {
      this.smsPackage_choice = id
      this.smsPackage_arr.find(item => {
        if (item.id === id) {
          this.smsPackage_choicePrice = item.money
          this.smsPackage_choiceNumber = item.number
        } else {}
      })
    },
    // 获取套餐列表
    getPackageList () {
      const pageNum = 1
      const data = {
        'pageNum': pageNum,
        'search': '{"status":"' + 1 + '"}'
      }
      SmsPackageList(data).then(res => {
        if (res.data.msg === 'ok') {
          var resData = res.data.data
          var length = parseInt(resData.length) - 1
          if (resData.length > 0) {
            this.smsPackage_choice = resData[length].id
            this.smsPackage_choicePrice = resData[length].money
            this.smsPackage_choiceNumber = resData[length].number
          }
          resData.forEach((val, i) => {
            this.smsPackage_arr.push(val)
          })
        } else { this.$Message.error(res.data.msg) }
      })
    }
  },
  mounted () {
    this.getPackageList()
  }
}
</script>

<style scoped>
  .smsLogo_div{
    text-align: right;
  }
  .smsLogo{
    margin-right: 15px;
    width: 80px;
    height: 80px;
  }
  .sms_title_div{
    text-align: left;
  }
  .sms_title{
    width: 80%;
  }
  .sms_title h1{
    font-size: 18px;
  }
  .sms_title p{
    margin-top: 10px;
    font-size: 13px;
    font-weight: 400;
    line-height: 24px;
  }
  .sms_trade{
    background-color: #f5f5f5;
    margin-top: 10px;
    display: flex;
  }
  .trade-amount{
    color: #ff6602;
    line-height: 40px;
    font-size: 16px;
    font-weight: 400;
    margin-left: 20px;
  }
  .sms_price{
    font-size: 35px;
    font-weight: 900;
  }
  .sms_rate{
    line-height: 48px;
    width: 100%;
    text-align: right;
  }
  .sms_contnet_div{
    margin-top: 15px;
  }
  /deep/ .sms_contnet_div .ivu-tag-border{
    line-height: 38px;
    height: 38px;
  }
  .sms_contnet{
    width: 80%;
  }
  .sms_label{
    margin-right: 15px;
  }
</style>
