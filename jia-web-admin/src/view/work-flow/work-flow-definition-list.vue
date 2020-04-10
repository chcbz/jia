<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="关键词">
                <Input v-model="searchList.key"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <a ref="downloadDiagram" id="downloadDiagram" style="display: none"></a>
      </div>
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <bpmn-resource ref="BpmnResource"></bpmn-resource>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import BpmnResource from './modal/bpmn-resource'
import { getWorkDefinition, activateWorkDefinition, suspendWorkDefinition, findWorkResource } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    BpmnResource
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        key: ''
      },
      columns: [
        { title: '定义名称', key: 'name' },
        { title: '资源名称', key: 'resourceName' },
        { title: '分类', key: 'category' },
        { title: '关键词', key: 'key' },
        { title: '版本', key: 'version' },
        { title: '描述', key: 'description' },
        { title: '状态',
          key: 'stateCode',
          render: (h, params) => {
            var data = params.row
            var stateCode = data.stateCode
            var result = this.$constant.getvalue('WorkDfStaus', stateCode)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          width: 280,
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var deploymentId = data.deploymentId
            var resourceName = data.resourceName
            var stateCode = data.stateCode
            var select = h('Button', {
              props: {
                type: 'success',
                size: 'small'
              },
              on: {
                click: () => {
                  this.FindWorkresource(deploymentId, resourceName)
                }
              }
            }, '查看')
            var update = h('Button', {
              props: {
                type: 'warning',
                size: 'small'
              },
              on: {
                click: () => {
                  this.$router.push({ path: 'update_deploy_work_flow/' + deploymentId, query: { name: resourceName } })
                }
              }
            }, '修改')
            var download = h('Button', {
              props: {
                size: 'small'
              },
              on: {
                click: () => {
                  this.handleDownload(data)
                }
              }
            }, '下载')
            var activate = h('Button', {
              props: {
                type: 'info',
                size: 'small',
                disabled: stateCode === 1
              },
              on: {
                click: () => {
                  this.handleActivate(data)
                }
              }
            }, '激活')
            var Suspend = h('Button', {
              props: {
                type: 'error',
                size: 'small',
                disabled: stateCode === 2
              },
              on: {
                click: () => {
                  this.handleSuspend(data)
                }
              }
            }, '挂起')
            return h('div', {
              class: {
                table_operate: true
              }
            }, [select, update, download, activate, Suspend])
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 获取工作流部署资源内容
    FindWorkresource (x, y) {
      findWorkResource(x, y).then(res => {
        this.$refs.BpmnResource.bpmn_resource_title = y + '流程图'
        this.$refs.BpmnResource.bpmn_resource_modal = true
        this.$refs.BpmnResource.bpmnXmlStr = res.data
      })
    },
    handleActivate (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '激活工作流定义'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否激活定义-' + name
      this.$refs.ready.ready_judge = 'activate'
      this.$refs.ready.ready_params = data
    },
    handleDownload (x) {
      var data = x
      var deploymentId = data.deploymentId
      var resourceName = data.resourceName
      findWorkResource(deploymentId, resourceName).then(res => {
        var xml = res.data
        // 把xml转换为URI，下载要用到的
        var encodedData = 'data:application/bpmn20-xml;charset=UTF-8,' + encodeURIComponent(xml)
        const downloadLink = this.$refs.downloadDiagram
        downloadLink.className = 'active'
        downloadLink.href = encodedData
        downloadLink.download = resourceName
        let downloadDiagram = document.getElementById('downloadDiagram')
        downloadDiagram.click()
      })
    },
    handleSuspend (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '挂起工作流定义'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否挂起定义-' + name
      this.$refs.ready.ready_judge = 'suspend'
      this.$refs.ready.ready_params = data
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getWorkDefinition(page, JSON.stringify(obj))
    },
    getWorkDefinition (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      getWorkDefinition(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 挂起、激活
    ParentNext () {
      var id = this.$refs.ready.ready_params.id
      var judge = this.$refs.ready.ready_judge
      this.$Spin.show()
      if (judge === 'activate') {
        activateWorkDefinition(id).then(res => {
          this.$Spin.hide()
          this.searchTable(1)
        })
      } else if (judge === 'suspend') {
        suspendWorkDefinition(id).then(res => {
          this.$Spin.hide()
          this.searchTable(1)
        })
      } else {}
    }
  },
  mounted () {
    this.searchTable(1)
  }
}
</script>
