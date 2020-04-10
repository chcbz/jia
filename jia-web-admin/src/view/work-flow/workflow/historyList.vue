<template>
  <div>
    <Card>
      <tables ref="tables"  border v-model="tableData"  :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
  </div>
</template>
<script>
import Tables from '_c/tables'
import { WorkflowHistoryList } from '@/api/data'

export default {
  components: {
    Tables
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        // { title: '流程代码', key: 'definitionKey' },
        { title: '流程名称', key: 'definitionName' },
        { title: '申请单号', key: 'businessKey' },
        { title: '当前处理人', key: 'assignee' },
        { title: '申请人', key: 'applicant' },
        {
          title: '申请时间',
          key: 'applicationDate',
          sortable: true,
          render: (h, params) => {
            var data = params.row
            var applicationDate = data.applicationDate
            var result = this.$constant.formatDateTime(applicationDate)
            return h('span', result)
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    WorkflowHistoryList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      var processInstanceId = this.$route.params.id
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': '{"processInstanceId":"' + processInstanceId + '"}'
      }
      WorkflowHistoryList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.WorkflowHistoryList(page)
    }
  },
  mounted () {
    this.WorkflowHistoryList(1)
  },
  watch: {
    // 方法1
    '$route' (to, from) { // 监听路由是否变化
      if (this.$route.params.id) { // 判断条件1  判断传递值的变化
        this.WorkflowHistoryList(1)
      }
    }
  }
}
</script>
