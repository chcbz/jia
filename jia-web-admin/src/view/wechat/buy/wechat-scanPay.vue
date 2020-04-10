<template>
  <div class="qrCode_div">
    <Card>
      <div class="qrCode">
        <canvas  ref ="canva" class="qrCode_code" :class="{qrCode_code_success:qrCodeModal}" ></canvas>
        <div :class="{qrCode_modal:qrCodeModal}" v-show="qrCodeModal">
          <img  :src="successImage"/>
        </div>
      </div>
      <div>
        <span class="qrCode_text">{{qrCode_text}}</span>
      </div>
    </Card>
  </div>
</template>
<script>
import successImage from '@/assets/images/pay-success.png'
import QrCode from 'qrcode'
import { WxScanPay, SmsBuyGet } from '@/api/data'
export default {
  components: {
  },
  data () {
    return {
      successImage,
      qrCode_text: '请扫描图中的二维码进行付款',
      qrCodeModal: false,
      timeSender: null
    }
  },
  methods: {
    useqrcode (url) {
      let canvas = this.$refs.canva
      QrCode.toCanvas(canvas, url, function (error) {
        if (error) console.error(error)
        // console.log('success!')
      })
    },
    getWxScanPay () {
      const productId = this.$route.query.productId
      const appid = 'wx9faba829e04f348b'
      var data = {
        'productId': productId,
        'appid': appid
      }
      WxScanPay(data).then(res => {
        var url = res.data
        this.useqrcode(url)
      })
    },
    getBuyMsg () {
      const id = this.$route.query.buyId
      SmsBuyGet(id).then(res => {
        if (res.data.msg === 'ok') {
          var rData = res.data.data
          var status = rData.status
          if (status === 1) {
            this.$Message.success('支付成功!')
            clearInterval(this.timeSender)
            this.qrCode_text = '支付成功!'
            this.qrCodeModal = true
          } else {}
        } else { this.$Message.error(res.data.msg) }
      })
    }
  },
  mounted () {
    this.getWxScanPay()
    this.timeSender = setInterval(() => {
      this.getBuyMsg()
    }, 3000)
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.query.productId) {
        this.getWxScanPay()
      }
    }
  },
  beforeDestroy () {
    clearInterval(this.timeSender)
  }
}
</script>

<style lang="less" scoped>
  .qrCode_div{
    margin: 0 auto;
    width: 500px;
    margin-top: 5%;
    text-align: center;
  }
  .qrCode{
    height: 246px;
    width: 246px;
    position: relative;
    margin: 0 auto 10px;
  }
  .qrCode_code{
    border: 1px solid #dee2e6;
    height: 100%!important;
    width: 100%!important;
  }
  .qrCode_code_success{
    filter: blur(1px);
  }
  .qrCode_modal{
    width: 100%;
    height: 100%;
    position: absolute;
    top: 0;
    right: 0;
    /*background-color: #5a5a5a;*/
    /*opacity: 0.5;*/
  }
  .qrCode_modal img{
    margin-top: 30%;
  }
  .qrCode_text{
    font-weight: 600;
    font-size: 18px;
  }
</style>
