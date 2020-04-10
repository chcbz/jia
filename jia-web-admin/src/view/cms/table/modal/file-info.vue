<template>
  <div>
    <Modal :title="FileInfo_title"
           v-model="FileInfo_modal"
           :closable="false"
           :mask-closable="false">
      <div>
        <Form ref="FileInfo_form"
              :model="FileInfo_form"
              :label-width="80"
              label-position="left">
          <FormItem>
            <Upload class="little"
                    :before-upload="UploadPic"
                    action="//jsonplaceholder.typicode.com/posts/"
                    :format="['jpg','jpeg','png']">
              <Button icon="ios-cloud-upload-outline">选择文件</Button>
            </Upload>
            <div v-if="FileInfo_form.pic !== null">{{ FileInfo_form.pic.name }}</div>
          </FormItem>
        </Form>
      </div>
      <div slot="footer">
        <Button type="text"
                size="large"
                @click="FileInfo_modal=false">取消</Button>
        <Button type="primary"
                size="large"
                v-show="FileInfo_form.pic"
                @click="next">确定</Button>
      </div>
    </Modal>
  </div>
</template>

<script>
import { uploadCmsFile } from '@/api/data'
export default {
  components: {
  },
  data () {
    return {
      FileInfo_title: '表格',
      FileInfo_modal: false,
      FileInfo_judge: '',
      FileInfo_form: {
        pic: ''
      }
    }
  },
  created: function () {
  },
  methods: {
    next () {
      this.$emit('operate', '')
    },
    // 上传封面图
    UploadPic (file) {
      this.FileInfo_form.pic = file
      return false
    }
  },
  mounted () {
    // var id = this.$route.params.id
    // this.eId = id
  }
}
</script>

<style scoped>
.little {
  width: 200px;
}
.pic_wrap img {
  width: 180px;
  height: auto;
}
</style>
