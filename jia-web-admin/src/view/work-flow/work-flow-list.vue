<template>
  <div>
    <Card>
      <tables ref="tables" border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import { getWorkList, delWorkflow } from '@/api/data'

export default {
  components: {
    Tables,
    Ready
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '部署名称', key: 'name' },
        { title: '分类', key: 'category' },
        { title: '日期', key: 'deploymentTime' },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            return h('div', {
              class: {
                table_operate: true
              }
            }, [
              h('Button', {
                props: {
                  type: 'info',
                  size: 'small'
                },
                on: {
                  click: () => {
                    var id = data.id
                    var name = data.name
                    this.$router.push({ path: 'work_flow_resource_list/' + id, query: { name: name } })
                  }
                }
              }, '查看'),
              h('Button', {
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
            ])
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除已部署工作流'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    getWorkList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      getWorkList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.getWorkList(page)
    },
    // 删除工作流部署信息
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delWorkflow(id).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.getWorkList(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    }
  },
  mounted () {
    this.getWorkList(1)
  }
}
</script>
