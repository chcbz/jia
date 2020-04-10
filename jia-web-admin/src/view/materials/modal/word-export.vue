<template>
  <Modal
    :title="WordExport_title"
    v-model="WordExport_modal"
    :closable="false"
    :mask-closable="false">
    <div class="word-other-wrap">
      <div id="resDom">
        <!--<img src="../../../assets/images/jia.jpg"/>-->
        <!--<img src="https://apia.jia.wydiy.com/file/res/20191008165147_image.png"/>-->
      </div>
      <!--<iframe-->
        <!--:src="resUrl"-->
        <!--class="word-iframe"-->
        <!--scrolling="auto"-->
        <!--frameborder="0"-->
        <!--id="iframeWord"-->
      <!--&gt;-->
      <!--</iframe>-->
    </div>
    <div slot="footer">
      <Button type="text" size="large" @click="WordExport_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">导出Word</Button>
    </div>
  </Modal>
</template>

<script>
import axios from '@/libs/api.request'
import wordExport from '@/libs/jquery.word.export'
import { getNews } from '@/api/data'

export default {
  data () {
    return {
      WordExport_title: '导出word文件',
      WordExport_modal: false,
      WordExport_id: '',
      resUrl: ''
    }
  },
  methods: {
    // 获取图文信息
    getNews () {
      var id = this.WordExport_id
      var data = {
        'id': id
      }
      getNews(data).then(res => {
        var arr = res.data.data
        var url = this.$constant.NewStaticUrl + arr.bodyurl
        this.resUrl = url
        axios.request({
          url: url,
          method: 'get'
        }).then(res => {
          var dom = res.data
          $('#resDom').html(dom)
          var imgArr = $('#resDom img')
          imgArr.each((i, val) => {
            var url = $(val).attr('src')
            this.$constant.getCanvasBase64(url)
              .then(function (base64) {
                $(val).attr('src', base64)
              }, function (err) {
                console.log(err)
              })
          })
        })
      })
    },
    next () {
      var xrules = ''
      var ss = document.styleSheets
      for (var i = 0; i < ss.length; ++i) {
        for (var x = 0; x < ss[i].cssRules.length; ++x) {
          xrules += ss[i].cssRules[x].cssText
        }
      }
      $('#resDom').wordExport('word1', xrules)
    },
    initVform () {}
  },
  watch: {
    WordExport_id () {
      this.getNews()
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style lang="less" scoped>
  /deep/ .ivu-modal{
    width: 67%!important;
  }
  /deep/ .ivu-modal-body{
    display: grid;
    min-height: 500px;
    max-height: 600px;
    overflow: auto;
  }
  .word-other-wrap{
    width: 100%;
    border: 1px solid #e6e0e6;
    padding: 10px;
    height: 100%;
  }
  .word-iframe{
    width: 100%;
    height: 100%;
  }
</style>
