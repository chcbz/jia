<template>
  <div>
    <Card>
      <tables ref="tables" editable searchable search-place="top" border v-model="tableData"  :columns="columns"  @ChangPage=" ParentChange"/>
      <Button style="margin: 10px 0;" type="primary" @click="exportExcel">导出为Csv文件</Button>
    </Card>
    <bpmn-resource ref="BpmnResource"></bpmn-resource>
  </div>
</template>
<script>
import Tables from '_c/tables'
import BpmnResource from '../modal/bpmn-resource'
import { getWorkResource, findWorkResource } from '@/api/data'

export default {
  name: 'tables_page',
  components: {
    Tables,
    BpmnResource
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '资源名称', key: 'name' },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          render: (h, params) => {
            var id = this.$route.params.id
            var name = params.row.name
            var type = name.split('.')[1]
            var dom
            if (type === 'bpmn') {
              dom = h('div', {
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
                      this.FindWorkresource(id, name)
                    }
                  }
                }, '查看')
              ])
            } else {
              dom = ''
            }
            return dom
          }
        }
      ],
      AlltableData: [],
      tableData: []
    }
  },
  methods: {
    exportExcel () {
      this.$refs.tables.exportCsv({
        filename: `table-${(new Date()).valueOf()}.csv`
      })
    },
    WorkResource () {
      const res_id = this.$route.params.id
      getWorkResource(res_id).then(res => {
        var data = res.data.data
        var tableArr = []
        var total = 0
        data.forEach(function (val, index) {
          var i = 1
          total += i
          var obj = { 'name': val }
          tableArr.push(obj)
        })
        this.AlltableData = tableArr
        var pageNum = this.pageNum
        var pageSize = this.pageSize
        this.$refs.tables.insidePageNum = pageNum
        this.$refs.tables.insidePageSize = pageSize
        var FindArr = this.CountPage(pageNum)
        var start = FindArr[0].start
        var end = FindArr[0].end
        this.tableData = this.AlltableData.slice(start, end)
        this.$refs.tables.insidePageCount = total
      })
    },
    // 计算页数
    CountPage (x) {
      var number = parseInt(x)
      var size = parseInt(this.pageSize)
      var end = number * size - 1
      var start = end - 4
      var arr = [{ start: start, end: end + 1 }]
      return arr
    },
    // 分页
    ParentChange (page) {
      const pageNum = page
      var FindArr = this.CountPage(pageNum)
      var start = FindArr[0].start
      var end = FindArr[0].end
      this.tableData = this.AlltableData.slice(start, end)
    },
    // 获取工作流部署资源内容
    FindWorkresource (x, y) {
      findWorkResource(x, y).then(res => {
        this.$refs.BpmnResource.bpmn_resource_title = y + '流程图'
        this.$refs.BpmnResource.bpmn_resource_modal = true
        this.$refs.BpmnResource.bpmnXmlStr = res.data
      })
    }
  },
  mounted () {
    this.WorkResource()
  },
  watch: {
    // 方法1
    '$route' (to, from) { // 监听路由是否变化
      if (this.$route.params.id) { // 判断条件1  判断传递值的变化
        this.WorkResource()
        // 获取文章数据
      }
    }
  }
}
</script>
