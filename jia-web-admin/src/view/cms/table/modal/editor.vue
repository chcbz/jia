<template>
  <div class="editor-wrapper">
    <div :id="'editor'+eId"></div>
  </div>
</template>

<script>
import { uploadCmsFile } from '@/api/data'
import Editor from 'wangeditor'
import 'wangeditor/release/wangEditor.min.css'
export default {
  name: 'Editor',
  props: {
    eId: {
      type: String,
      default: '0'
    },
    value: {
      type: String,
      default: ''
    }
  },
  data () {
    return {
      editor: ''
    }
  },
  watch: {
    eId () {
      this.initEditor(this.eId)
      // return `editor${this.eId}`
    }
  },
  methods: {
    initEditor (eId) {
      var _this = this
      // console.log(`#editor${eId}`)
      this.editor = new Editor(`#editor${eId}`)
      // this.editor.destroy()
      // 将图片大小限制为 5M
      this.editor.customConfig.uploadImgMaxSize = 5 * 1024 * 1024
      this.editor.customConfig.customUploadImg = function (files, insert) {
        // files 是 input 中选中的文件列表
        // insert 是获取图片 url 后，插入到编辑器的方法
        // 上传代码返回结果之后，将图片插入到编辑器中
        files.forEach((val, i) => {
          var dataPic = {
            'file': val
          }
          uploadCmsFile(dataPic).then(res => {
            if (res.data.msg === 'ok') {
              var url = res.data.data.uri
              var imgUrl = _this.$constant.StaticUrl + url
              console.log(imgUrl)
              insert(imgUrl)
            } else { _this.$Message.error(res.data.msg) }
          }).catch(ess => {
            _this.$Message.error('请联系管理员')
          })
        })
      }
      // create这个方法一定要在所有配置项之后调用
      this.editor.create()
      // this.editor.txt.clear()
      let html = this.value
      if (html) {
        console.log(html)
        this.setHtml(html)
        // this.editor.txt.html(html)
      } else {}
    },
    setHtml (val) {
      this.editor.txt.html(val)
    },
    getHtml () {
      var res = this.editor.txt.html()
      return res
    }
  },
  mounted () {
    this.initEditor(this.eId)
  }
}
</script>

<style lang="less" scoped>
.editor-wrapper{
  width: 80%;
  position: relative;
  z-index: 100;
}
/deep/ .w-e-text-container{
  background: #fff;
  min-height: 500px;
}
/deep/ .w-e-text{
  min-height: 500px;
}
</style>
