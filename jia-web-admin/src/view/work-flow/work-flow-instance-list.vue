<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <!-- <FormItem label="流程代码">
                <Input v-model="searchList.definitionKey"></Input>
              </FormItem> -->
              <FormItem label="流程名称">
                <Input v-model="searchList.definitionName"></Input>
              </FormItem>
              <FormItem label="申请单号">
                <Input v-model="searchList.businessKey"></Input>
              </FormItem>
              <FormItem label="申请人">
                <Input v-model="searchList.applicant"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
      </div>
      <tables ref="tables"   border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready-reason ref="readyReason" @NextReason="ParentNext"></ready-reason>
  </div>
</template>
<script>
import Tables from '_c/tables'
import readyReason from '_c/modal/ready_reason'
import { WorkflowInstanceList, delWorkflowInstance } from '@/api/data'

export default {
  components: {
    Tables,
    readyReason
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        // definitionKey: '',
        definitionName: '',
        businessKey: '',
        applicant: ''
      },
      columns: [
        // { title: '最新任务', key: 'name' },
        // { title: '流程代码', key: 'definitionKey' },
        { title: '流程名称', key: 'definitionName' },
        { title: '申请单号', key: 'businessKey' },
        { title: '申请人', key: 'applicant' },
        { title: '申请时间', key: 'startTime' },
        { title: '审批完成时间', key: 'endTime' },
        {
          title: '操作',
          key: 'operate',
          width: 150,
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var endTime = data.endTime
            var select = h('Button', {
              props: {
                type: 'info',
                size: 'small'
              },
              on: {
                click: () => {
                  var id = data.id
                  var businessKey = data.businessKey
                  this.$router.push({ path: 'work_flow_history_list/' + id, query: { businessKey: businessKey } })
                }
              }
            }, '查看')
            var del = h('Button', {
              props: {
                type: 'error',
                size: 'small',
                disabled: !!endTime
              },
              on: {
                click: () => {
                  this.handleDelete(data)
                }
              }
            }, '删除')
            return h('div', {
              class: {
                table_operate: true
              }
            }, [select, del])
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
      this.WorkflowInstanceList(page, JSON.stringify(obj))
    },
    handleDelete (x) {
      var data = x
      var businessKey = data.businessKey
      this.$refs.readyReason.readyReason_title = '删除工作流实例-' + businessKey
      this.$refs.readyReason.$refs.readyReason_form.resetFields()
      this.$refs.readyReason.readyReason_modal = true
      this.$refs.readyReason.readyReason_params = data
    },
    WorkflowInstanceList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      WorkflowInstanceList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除工作流实例信息
    ParentNext () {
      var processInstanceId = this.$refs.readyReason.readyReason_params.id
      var deleteReason = this.$refs.readyReason.readyReason_form.deleteReason
      this.$refs.readyReason.$refs.readyReason_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          delWorkflowInstance(processInstanceId, deleteReason).then(res => {
            this.$Spin.hide()
            if (res.data.msg === 'ok') {
              this.$refs.readyReason.readyReason_modal = false
              this.$Message.success('成功删除')
              this.searchTable(1)
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
    // getUser (id) {
    //   var data = {
    //     'type': 'id',
    //     'key': id
    //   }
    //   this.$constant.getUser(data).then(res => {
    //     if (res.data.msg === 'ok') {
    //       var nickname = res.data.data.nickname
    //     } else {}
    //   })
    // }
  },
  mounted () {
    this.searchTable(1)
  }
}
</script>
