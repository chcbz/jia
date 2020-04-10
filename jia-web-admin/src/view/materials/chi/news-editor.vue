<template>
  <div>
    <Form ref="news_form" :model="news_form" :rules="ruleValidate"  :label-width="80" label-position="left">
      <FormItem label="标题" prop="title">
        <Input class="little"  v-model="news_form.title"></Input>
      </FormItem>
      <FormItem label="作者" prop="author">
        <Input class="little"  v-model="news_form.author"></Input>
      </FormItem>
      <FormItem label="摘要">
        <Input class="little"  v-model="news_form.digest"></Input>
      </FormItem>
      <FormItem label="封面图片">
        <Upload
          class="little"
          :before-upload="UploadPic"
          action="//jsonplaceholder.typicode.com/posts/"
          :format="['jpg','jpeg','png']"
        >
          <Button icon="ios-cloud-upload-outline">封面图</Button>
        </Upload>
        <div v-if="showPic !== ''" class="pic_wrap">
          <img :src="showPic"/>
        </div>
      </FormItem>
      <FormItem label="正文">
        <editor ref="editor" :eId="eId" :value="EditorContent"/>
      </FormItem>
      <FormItem>
        <Button type="primary" @click="confirm" style="margin: 0 10px;">确认</Button>
        <Button type="info" @click="ParentSend" :disabled="!news_id" style="margin: 0 10px;">发送</Button>
        <Button  @click="ExportWord" :disabled="!news_id">导出</Button>
      </FormItem>
    </Form>
    <Send-modal ref="SendModal"></Send-modal>
    <Word-modal ref="WordModal"></Word-modal>
  </div>
</template>

<script>
import Editor from '_c/editor'
import { createNews, uploadMedia } from '@/api/data'
import SendModal from '../modal/send-template'
import WordModal from '../modal/word-export'
export default {
  components: {
    Editor,
    SendModal,
    WordModal
  },
  data () {
    return {
      eId: '',
      EditorContent: '',
      newsType: [],
      news_id: '',
      news_form: {
        title: '',
        content: '',
        author: '',
        digest: '',
        pic: ''
      },
      showPic: '',
      ruleValidate: {
      }
    }
  },
  created: function () {
    this.initVue()
  },
  methods: {
    initVue () {
      this.initVform()
    },
    initVform () {
      var v_arr = [
        { 'name': 'title', 'method': 'NotNull' },
        { 'name': 'author', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    },
    // 导出
    ExportWord () {
      this.$refs.WordModal.WordExport_id = this.news_id
      this.$refs.WordModal.WordExport_modal = true
    },
    // 发送
    ParentSend () {
      this.$refs.SendModal.SendTemplate_templateId = this.news_id
      this.$refs.SendModal.SendTemplate_modal = true
    },
    // 上传封面图
    UploadPic (file) {
      this.news_form.pic = file
      var reads = new FileReader()
      reads.readAsDataURL(file)
      var _this = this
      reads.onload = function (e) {
        _this.showPic = this.result
      }
      return false
    },
    // 确认创建
    confirm () {
      var id = this.$route.params.id
      var title = this.news_form.title
      var author = this.news_form.author
      var digest = this.news_form.digest
      var file = this.news_form.pic
      var content = this.$refs.editor.getHtml()
      if (!content || content === '<p><br></p>') {
        this.$Message.error('正文不能为空')
        return false
      } else if (!file) {
        this.$Message.error('封面不能为空')
        return false
      } else {}
      if (id > 0) {} else {
        this.$refs.news_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            var dataPic = {
              'title': file.name,
              'type': 1,
              'file': file
            }
            var dataContent = {
              'title': 'editorT.html',
              'type': 5,
              'content': content
            }
            var dataNews = {
              'title': title,
              'author': author,
              'digest': digest
            }
            uploadMedia(dataPic).then(res => {
              if (res.data.msg === 'ok') {
                // this.$Spin.hide()
                // this.$Message.success('成功上传封面图')
                var picurl = res.data.data.url
                dataNews['picurl'] = picurl
                uploadMedia(dataContent).then(res => {
                  if (res.data.msg === 'ok') {
                    // this.$Spin.hide()
                    var bodyurl = res.data.data.url
                    dataNews['bodyurl'] = bodyurl
                    // this.$Message.success('成功上传正文')
                    createNews(dataNews).then(res => {
                      if (res.data.msg === 'ok') {
                        this.$Spin.hide()
                        this.$Message.success('成功创建新闻')
                        this.news_id = res.data.data.id
                      } else {
                        this.$Spin.hide()
                        this.$Message.error(res.data.msg)
                      }
                    }).catch(ess => {
                      this.$Spin.hide()
                      this.$Message.error('请联系管理员')
                    })
                  } else {
                    this.$Spin.hide()
                    this.$Message.error(res.data.msg)
                  }
                }).catch(ess => {
                  this.$Spin.hide()
                  this.$Message.error('请联系管理员')
                })
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
    }
  },
  mounted () {
    // var id = this.$route.params.id
    // this.eId = id
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.id) {
        // var id = this.$route.params.id
        // this.eId = id
      }
    }
  }
}
</script>

<style scoped>
.little{
  width: 200px;
}
.pic_wrap img{
  width: 180px;
  height: auto;
}
</style>
