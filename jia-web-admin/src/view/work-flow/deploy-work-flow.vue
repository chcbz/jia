<template>
  <div  class="content">
    <Bpmn ref="bpmn" @BpmnSave="WrtieBpmn" @fileUpload="UploadBpmn"></Bpmn>
    <work-flow ref="WorkFlow" @Next="SaveBpmn"></work-flow>
  </div>
</template>

<script>
import Bpmn from '_c/bpmn'
import WorkFlow from './modal/work-flow'
import { WorkflowDeploy } from '@/api/data'
export default {
  name: 'deploy-work-flow',
  components: {
    Bpmn,
    WorkFlow
  },
  data () {
    return {
      file: null,
      uploadFile: null
    }
  },
  methods: {
    // 传入文件部署
    UploadBpmn (file) {
      this.uploadFile = file
      var file_format = file.name.split('.')[1]
      if (file_format === 'zip' || file_format === 'bpmn') {
        this.$refs.WorkFlow.$refs.work_form.resetFields()
        this.$refs.WorkFlow.work_form.name = ''
        this.$refs.WorkFlow.work_form.category = ''
        this.$refs.WorkFlow.work_form.key = ''
        this.$refs.WorkFlow.work_Validate = 'upload'
        this.$refs.WorkFlow.work_modal = true
      } else { this.$Message.error('上传的文件格式有误') }
    },
    // 部署工作流
    WrtieBpmn (file) {
      if (file === null) {
        this.$Message.error('请先编辑流程图！')
      } else {
        this.$refs.WorkFlow.$refs.work_form.resetFields()
        this.$refs.WorkFlow.work_form.name = ''
        this.$refs.WorkFlow.work_form.category = ''
        this.$refs.WorkFlow.work_form.key = ''
        this.$refs.WorkFlow.work_Validate = ''
        this.$refs.WorkFlow.work_modal = true
        this.file = file
        // console.log(file)
      }
    },
    SaveBpmn () {
      this.$refs.WorkFlow.$refs.work_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          var name = this.$refs.WorkFlow.work_form.name
          var file_name = name + '.bpmn'
          var file = (this.$refs.WorkFlow.work_Validate === 'upload') ? this.uploadFile : this.$constant.dataURLtoFile(this.file, file_name)
          var category = this.$refs.WorkFlow.work_form.category
          var key = this.$refs.WorkFlow.work_form.key
          WorkflowDeploy(file, name, category, key).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$Message.success('成功上传')
              this.$refs.WorkFlow.work_modal = false
              this.$refs.bpmn.fileName = file.name
            } else { this.$Message.error(res.data.msg) }
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

<style scoped>
  .content{
    height: 100%;
    width: 100%;
  }
</style>
