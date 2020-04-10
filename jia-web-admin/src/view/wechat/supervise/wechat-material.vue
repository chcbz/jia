<template>
  <div>
    <Card>
      <Tabs type="card">
        <TabPane label="图文消息">
          <div class="tofu_wrap">
            <Card class="tofu">
                <div class="news_first">
                  <img class="news_first_img" src="http://mmbiz.qpic.cn/mmbiz_jpg/1cS7Cdg35upXarh2XwoMZeDzlzeUDMw1ialfmfaIdWR09Wibk8VnYO6ib1rRuSlUUlibQETgywjAtNic4wSyyM6nZRw/0?wx_fmt=jpeg"/>
                  <div class="news_first_title"><span>加油</span></div>
                </div>
              <div class="news_div">
                <div class="news_div_left"><span>加油</span></div>
                <div class="news_div_right"><img class="news_div_img" src="../../../assets/images/login-bg.jpg"/></div>
              </div>
              <div class="news_time">
                <span>最新更新时间:2019-02-24</span>
              </div>
            </Card>
          </div>
        </TabPane>
        <TabPane label="图片">标签二的内容</TabPane>
      </Tabs>
    </Card>
  </div>
</template>
<script>
import { WxMpMaterial } from '@/api/data'

export default {
  components: {
  },
  data () {
    return {
      count: 5,
      modal_i: ''
    }
  },
  methods: {
    getWxMpMaterial (x, y) {
      const appid = this.$route.params.appid
      var type = x
      var offset = y
      var count = this.count
      var data = {
        'type': type,
        'offset': offset,
        'count': count
      }
      WxMpMaterial(appid, data).then(res => {
        var Mdata = res.data.data
        console.log(Mdata)
      })
    }
  },
  mounted () {
    this.getWxMpMaterial('news', 0)
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.appid) {
        this.getWxMpMaterial('news', 0)
      }
    }
  }
}
</script>

<style lang="less" scoped>
  /*遮罩层*/
  .material_modal{
    opacity:0.5;
    filter: alpha(opacity=60);
  }
  .tofu{
    width: 300px;
    margin: 3px;
    float: left;
  }
  .news_first{
    position: relative;
    width: 100%;
    height: 150px;
    cursor: pointer;
  }
  .news_first_img{
    width: 100%;
    height: 100%;
  }
  .news_first_title{
    color: #FFFFFF;
    background-color: rgba(0, 0, 0, 0.55);
    position: absolute;
    left: 0;
    right: 0;
    bottom: 1px;
    padding: 8px 12px;
  }
  .news_div{
    padding: 5px;
    width: 100%;
    height: 80px;
    border-top: 1px solid #b7b8b9;
    cursor: pointer;
    margin-top: 3px;
    display: flex;
  }
  .news_div_left{
    width: 60%;
  }
  .news_div_right{
    width: 40%;
  }
  .news_div_img{
    width: 100%;
    height: 100%;
  }
  .news_div:hover{
    background: #efebeb;
  }
  .news_time{
    border-top: 1px solid #e4e6e8;
    margin-top: 2px;
    padding: 5px;
  }
</style>
