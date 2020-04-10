<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="模块">
                <Input v-model="searchList.module"></Input>
              </FormItem>
              <FormItem label="功能">
                <Input v-model="searchList.func"></Input>
              </FormItem>
              <FormItem label="级别">
                <Select v-model="searchList.level" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in ActionLevel" :value="option.id" :key="v">{{option.str}}</Option>
                </Select>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
      </div>
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <action-modal ref="ActionModal" @operate="ParentOperate"></action-modal>
  </div>
</template>
<script>
import Tables from '_c/tables'
import { getPermsList, updateAction } from '@/api/data'
import ActionModal from './modal/action-template'

export default {
  components: {
    Tables,
    ActionModal
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        module: '',
        func: '',
        level: ''
      },
      ActionLevel: [],
      columns: [
        { title: '模块', key: 'module' },
        { title: '功能', key: 'func' },
        { title: '地址', key: 'url' },
        { title: '描述', key: 'description' },
        { title: '状态',
          key: '级别',
          render: (h, params) => {
            var data = params.row
            var level = data.level
            var result = this.$constant.getvalue('ActionLevel', level)
            return h('span', result)
          }
        },
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
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [update])
            return result
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 修改服务器
    handleUpdate (x) {
      var data = x
      var id = data.id
      var level = data.level
      var description = data.description
      this.$refs.ActionModal.$refs.Action_form.resetFields()
      this.$refs.ActionModal.Action_modal = true
      this.$refs.ActionModal.Action_Id = id
      this.$refs.ActionModal.Action_form.level = level
      this.$refs.ActionModal.Action_form.description = description
      this.$refs.ActionModal.Action_judge = 'update'
    },
    ParentOperate () {
      var judge = this.$refs.ActionModal.Action_judge
      var id = this.$refs.ActionModal.Action_Id
      var level = this.$refs.ActionModal.Action_form.level
      var description = this.$refs.ActionModal.Action_form.description
      var data = {
        'level': level,
        'description': description
      }
      if (judge === 'create') {} else if (judge === 'update') {
        this.$refs.ActionModal.$refs.Action_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            updateAction(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.ActionModal.Action_modal = false
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
      } else {}
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getPermsList(page, JSON.stringify(obj))
    },
    getPermsList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      getPermsList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 初始化
    initView () {
      this.ActionLevel = this.$constant.ActionLevel
      this.searchTable(1)
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    }
  },
  mounted () {
    this.initView()
  }
}
</script>

<style scoped>

</style>
