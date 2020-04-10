<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="模板类型">
                <Select v-model="searchList.type" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in SmsTemplateType" :value="option.id" :key="v">{{option.str}}</Option>
                </Select>
              </FormItem>
              <!--<FormItem label="状态">-->
              <!--<Select v-model="searchList.status" class="search_select" clearable filterable>-->
              <!--<Option v-for="(option, v) in SmsTemplateStatus" :value="option.id" :key="v">{{option.str}}</Option>-->
              <!--</Select>-->
              <!--</FormItem>-->
              <FormItem label="名称">
                <Input v-model="searchList.name"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 10px;" type="success" @click="handleCreate">创建短信模板</Button>
      </div>
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <template-modal ref="TemplateModal" @operate="ParentOperate"></template-modal>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import TemplateModal from './modal/sms-template'
import { createSmsTemplate, updateSmsTemplate, delSmsTemplate, SmsTemplateList } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    TemplateModal
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        type: '',
        status: '',
        name: ''
      },
      SmsTemplateType: [],
      // SmsBuyStatus: [],
      columns: [
        { title: '模板ID', key: 'templateId' },
        { title: '模板名称', key: 'name' },
        { title: '内容', key: 'content' },
        { title: '类型',
          key: 'type',
          render: (h, params) => {
            var data = params.row
            var type = data.type
            var result = this.$constant.getvalue('SmsTemplateType', type)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var clientId = data.clientId
            var update = h('Button', {
              props: {
                type: 'info',
                size: 'small',
                disabled: !clientId
              },
              on: {
                click: () => {
                  this.handleUpdate(data)
                }
              }
            }, '修改')
            var del = h('Button', {
              props: {
                type: 'error',
                size: 'small',
                disabled: !clientId
              },
              on: {
                click: () => {
                  this.handleDel(data)
                }
              }
            }, '删除')
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [update, del])
            return result
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      obj['status'] = 1
      obj['msgType'] = 3
      this.getTemplateList(page, JSON.stringify(obj))
    },
    // 初始化
    initView () {
      this.SmsTemplateType = this.$constant.SmsTemplateType
      // this.SmsTemplateStatus = this.$constant.SmsTemplateStatus
    },
    // 创建短信模板
    handleCreate () {
      this.$refs.TemplateModal.$refs.SmsTemplate_form.resetFields()
      this.$refs.TemplateModal.SmsTemplate_title = '创建短信模板'
      this.$refs.TemplateModal.SmsTemplate_modal = true
      this.$refs.TemplateModal.SmsTemplate_templateId = ''
      this.$refs.TemplateModal.SmsTemplate_form.name = ''
      this.$refs.TemplateModal.SmsTemplate_form.content = ''
      this.$refs.TemplateModal.SmsTemplate_form.type = '1'
      this.$refs.TemplateModal.SmsTemplate_judge = 'create'
    },
    // 修改短信模板
    handleUpdate (x) {
      var data = x
      var templateId = data.templateId
      var name = data.name
      var content = data.content
      var type = data.type.toString()
      this.$refs.TemplateModal.$refs.SmsTemplate_form.resetFields()
      this.$refs.TemplateModal.SmsTemplate_title = '修改短信模板'
      this.$refs.TemplateModal.SmsTemplate_modal = true
      this.$refs.TemplateModal.SmsTemplate_templateId = templateId
      this.$refs.TemplateModal.SmsTemplate_form.name = name
      this.$refs.TemplateModal.SmsTemplate_form.content = content
      this.$refs.TemplateModal.SmsTemplate_form.type = type
      this.$refs.TemplateModal.SmsTemplate_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.TemplateModal.SmsTemplate_judge
      var name = this.$refs.TemplateModal.SmsTemplate_form.name
      var content = this.$refs.TemplateModal.SmsTemplate_form.content
      var type = this.$refs.TemplateModal.SmsTemplate_form.type
      var templateId = this.$refs.TemplateModal.SmsTemplate_templateId
      var data = {
        'name': name,
        'content': content,
        'type': type,
        'msgType': 3
      }
      if (judge === 'create') {
        this.$refs.TemplateModal.$refs.SmsTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createSmsTemplate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功创建')
                this.$refs.TemplateModal.SmsTemplate_modal = false
                this.searchTable(1)
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
      } else if (judge === 'update') {
        this.$refs.TemplateModal.$refs.SmsTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['templateId'] = templateId
            updateSmsTemplate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                this.$refs.TemplateModal.SmsTemplate_modal = false
                this.searchTable(1)
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
      } else {}
    },
    // 获取短信模板
    getTemplateList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      SmsTemplateList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除短信模板
    handleDel (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除短信模板'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除短信模板-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var templateId = this.$refs.ready.ready_params.templateId
      delSmsTemplate(templateId).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.searchTable(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    }
  },
  mounted () {
    this.initView()
    this.searchTable(1)
  }
}
</script>

<style scoped>

</style>
