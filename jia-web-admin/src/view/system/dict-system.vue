<template>
  <div>
    <Card>
      <Button style="margin: 10px 0;" type="success" @click="handleCreate">新增字典</Button>
      <tables ref="tables" border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <dict-modal ref="DictModal" @operate="ParentOperate"></dict-modal>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import DictModal from './modal/dict-template'
import { createDict, updateDict, getDictTable, delDict } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    DictModal
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '名称', key: 'name' },
        { title: '内容', key: 'value' },
        { title: '描述', key: 'description' },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var update = h('Button', {
              props: {
                type: 'info',
                size: 'small'
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
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleDelete(data)
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
    // 创建字典
    handleCreate () {
      this.$refs.DictModal.$refs.DictTemplate_form.resetFields()
      this.$refs.DictModal.DictTemplate_title = '创建字典'
      this.$refs.DictModal.DictTemplate_modal = true
      this.$refs.DictModal.DictTemplate_templateId = ''
      this.$refs.DictModal.DictTemplate_form.name = ''
      this.$refs.DictModal.DictTemplate_form.value = ''
      this.$refs.DictModal.DictTemplate_form.dictOrder = ''
      this.$refs.DictModal.DictTemplate_form.parentId = ''
      this.$refs.DictModal.DictTemplate_form.status = '1'
      this.$refs.DictModal.DictTemplate_form.description = ''
      this.$refs.DictModal.DictTemplate_judge = 'create'
    },
    // 修改字典
    handleUpdate (x) {
      var data = x
      var templateId = data.id
      var name = data.name
      var value = data.value
      var dictOrder = data.dictOrder
      var parentId = parseInt(data.parentId)
      var status = data.status.toString()
      var description = data.description
      this.$refs.DictModal.$refs.DictTemplate_form.resetFields()
      this.$refs.DictModal.DictTemplate_title = '修改字典'
      this.$refs.DictModal.DictTemplate_modal = true
      this.$refs.DictModal.DictTemplate_form.name = name
      this.$refs.DictModal.DictTemplate_form.value = value
      this.$refs.DictModal.DictTemplate_form.dictOrder = dictOrder
      this.$refs.DictModal.DictTemplate_form.parentId = parentId
      this.$refs.DictModal.DictTemplate_form.status = status
      this.$refs.DictModal.DictTemplate_form.description = description
      this.$refs.DictModal.DictTemplate_templateId = templateId
      this.$refs.DictModal.DictTemplate_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.DictModal.DictTemplate_judge
      var name = this.$refs.DictModal.DictTemplate_form.name
      var value = this.$refs.DictModal.DictTemplate_form.value
      var dictOrder = this.$refs.DictModal.DictTemplate_form.dictOrder
      var parentId = (!this.$refs.DictModal.DictTemplate_form.parentId === true) ? '' : this.$refs.DictModal.DictTemplate_form.parentId
      var status = this.$refs.DictModal.DictTemplate_form.status
      var description = this.$refs.DictModal.DictTemplate_form.description
      var templateId = this.$refs.DictModal.DictTemplate_templateId
      var data = {
        'name': name,
        'value': value,
        'dictOrder': dictOrder,
        'parentId': parentId,
        'status': status,
        'description': description
      }
      if (judge === 'create') {
        this.$refs.DictModal.$refs.DictTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createDict(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.DictModal.DictTemplate_modal = false
                this.getDict()
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else if (judge === 'update') {
        this.$refs.DictModal.$refs.DictTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = templateId
            updateDict(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.DictModal.DictTemplate_modal = false
                this.getDict()
              } else { this.$Message.error(res.data.msg) }
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
    // 删除字典元素
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除' + name
      this.$refs.ready.ready_params = data
    },
    getDict (x) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      getDictTable(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.getDict(page)
    },
    // 删除字典
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delDict(id).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.getDict(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    }
  },
  mounted () {
    this.getDict(1)
  }
}
</script>
