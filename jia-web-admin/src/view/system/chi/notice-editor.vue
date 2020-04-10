<template>
  <div>
    <Form ref="notice_form" :model="notice_form" :rules="ruleValidate"  :label-width="80" label-position="left">
      <FormItem label="标题" prop="title">
        <Input class="little"  v-model="notice_form.title"></Input>
      </FormItem>
      <FormItem label="类型" prop="type">
        <Select class="little" v-model="notice_form.type" filterable>
          <Option v-for="(option, v) in noticeType" :value="option.value" :key="v">{{option.name}}</Option>
        </Select>
      </FormItem>
      <FormItem label="正文">
        <editor ref="editor" :eId="eId" :value="EditorContent"/>
      </FormItem>
      <FormItem>
        <Button type="primary" @click="confirm">确认</Button>
      </FormItem>
    </Form>
  </div>
</template>

<script>
import Editor from '_c/editor'
import { getDictTable, getNotice, createNotice, updateNotice } from '@/api/data'
export default {
  components: {
    Editor
  },
  data () {
    return {
      eId: '',
      EditorContent: '',
      noticeType: [],
      notice_form: {
        title: '',
        content: '',
        type: ''
      },
      ruleValidate: {
      }
    }
  },
  created: function () {
    this.initVue()
  },
  methods: {
    initVue () {
      this.getNoticeType()
      this.initVform()
    },
    initVform () {
      var v_arr = [
        { 'name': 'type', 'method': 'NotNull' },
        { 'name': 'title', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
    },
    // 获取公告信息
    NoticeGet (id) {
      if (id > 0) {
        getNotice(id).then(res => {
          this.$refs.notice_form.resetFields()
          var data = res.data.data
          this.notice_form.title = data.title
          this.notice_form.type = data.type.toString()
          this.notice_form.content = data.content
          this.EditorContent = data.content
          this.$refs.editor.setHtml(data.content)
        })
      } else {
        this.$refs.notice_form.resetFields()
        this.notice_form.title = ''
        this.notice_form.type = ''
        this.notice_form.content = ''
        this.EditorContent = ''
        this.$refs.editor.setHtml('')
      }
    },
    // 获取标签类型
    getNoticeType () {
      const pageNum = 1
      const pageSize = 999
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': '{"type":"NOTICE_TYPE"}'
      }
      var noticeArr = []
      getDictTable(data).then(res => {
        var resData = res.data.data
        resData.forEach((val, i) => {
          var value = val.value
          var name = val.name
          var obj = {
            value: value,
            name: name
          }
          noticeArr.push(obj)
        })
        this.noticeType = noticeArr
      })
    },
    confirm () {
      var id = this.$route.params.id
      var title = this.notice_form.title
      var type = this.notice_form.type
      var content = this.$refs.editor.getHtml()
      if (!content || content === '<p><br></p>') {
        this.$Message.error('正文不能为空')
        return false
      } else {}
      var data = {
        'title': title,
        'type': type,
        'content': content
      }
      if (id > 0) {
        this.$refs.notice_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            updateNotice(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
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
      } else {
        this.$refs.notice_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createNotice(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功创建')
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
    var id = this.$route.params.id
    // this.eId = id
    this.NoticeGet(id)
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.id) {
        var id = this.$route.params.id
        // this.eId = id
        this.NoticeGet(id)
      }
    }
  }
}
</script>

<style scoped>
.little{
  width: 200px;
}
</style>
