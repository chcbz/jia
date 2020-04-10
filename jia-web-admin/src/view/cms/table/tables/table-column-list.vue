<template>
  <div>
    <Card>
      <Button style="margin: 10px 0;" type="success" @click="handleCreate">新增数据列</Button>
      <tables ref="tables" editable  border v-model="tableData" :columns="columns"  @ChangPage="ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <table-column-info ref="TableColumnInfo" @operate="ParentOperate"></table-column-info>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import TableColumnInfo from '../modal/table-column-info'
import { cmsColumnList, createCmsColumn, updateCmsColumn, delColumn } from '@/api/data'

export default {
  name: 'tables_page',
  components: {
    Tables,
    Ready,
    TableColumnInfo
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 999,
      columns: [
        { title: '列名',
          key: 'name'
        },
        { title: '中文名',
          key: 'remark'
        },
        { title: '列类型',
          key: 'type'
        },
        { title: '长度',
          key: 'precision'
        },
        { title: '精度',
          key: 'scale'
        },
        { title: '是否作为搜索条件',
          key: 'isSearch',
          render: (h, params) => {
            var data = params.row
            var isSearch = data.isSearch
            var result = this.$constant.getvalue('CmsTableIsSearch', isSearch)
            return h('span', result)
          }
        },
        { title: '是否列表显示',
          key: 'isList',
          render: (h, params) => {
            var data = params.row
            var isList = data.isList
            var result = this.$constant.getvalue('CmsTableIsList', isList)
            return h('span', result)
          }
        },
        { title: '是否非空',
          key: 'notnull',
          render: (h, params) => {
            var data = params.row
            var notnull = data.notnull
            var result = this.$constant.getvalue('CmsTableNotNull', notnull)
            return h('span', result)
          }
        },
        { title: '默认值',
          key: 'defaultValue'
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
    // 创建数据列
    handleCreate () {
      this.$refs.TableColumnInfo.$refs.colFormList.resetFields()
      this.$refs.TableColumnInfo.TableColumnInfo_title = '新增数据列'
      this.$refs.TableColumnInfo.TableColumnInfo_judge = 'create'
      var arr = this.$refs.TableColumnInfo.colFormList.items
      arr.splice(0, arr.length)
      arr.push(
        {
          index: 0,
          name: '',
          type: 'int',
          precision: '',
          scale: '',
          notnull: 0,
          isSearch: 0,
          isList: 1,
          selectRange: null,
          defaultValue: '',
          remark: ''
        }
      )
      this.$refs.TableColumnInfo.TableColumnInfo_modal = true
    },
    // 修改表格
    handleUpdate (x) {
      this.$refs.TableColumnInfo.$refs.colFormList.resetFields()
      this.$refs.TableColumnInfo.TableColumnInfo_title = '修改数据列'
      this.$refs.TableColumnInfo.TableColumnInfo_id = x.id
      this.$refs.TableColumnInfo.TableColumnInfo_judge = 'update'
      var arr = this.$refs.TableColumnInfo.colFormList.items
      arr.splice(0, arr.length)
      arr.push(
        {
          index: 0,
          name: x.name,
          type: x.type,
          precision: x.precision,
          scale: x.scale,
          notnull: x.notnull,
          isSearch: x.isSearch,
          isList: x.isList,
          selectRange: x.selectRange,
          defaultValue: x.defaultValue,
          remark: x.remark
        }
      )
      this.$refs.TableColumnInfo.TableColumnInfo_modal = true
    },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.TableColumnInfo.TableColumnInfo_judge
      var arr = this.$refs.TableColumnInfo.colFormList.items
      arr.forEach(function (val, i) {
        arr[i].scale = (val.type !== 'double') ? null : val.scale
        arr[i].defaultValue = (val.defaultValue === '') ? null : val.defaultValue
        delete arr[i].index
      })
      var id = this.$refs.TableColumnInfo.TableColumnInfo_id
      var tableId = parseInt(this.$route.params.tableId)
      var columns = arr[0]
      columns['tableId'] = tableId
      if (judge === 'create') {
        this.$refs.TableColumnInfo.$refs.colFormList.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createCmsColumn(columns).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功创建')
                this.$refs.TableColumnInfo.TableColumnInfo_modal = false
                this.getColumnList()
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
      } else {
        this.$refs.TableColumnInfo.$refs.colFormList.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            columns['id'] = id
            updateCmsColumn(columns).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                this.$refs.TableColumnInfo.TableColumnInfo_modal = false
                this.getColumnList()
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
      }
    },
    // 获取数据列列表
    getColumnList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      const tableId = this.$route.params.tableId
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': { 'tableId': tableId }
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      cmsColumnList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.getColumnList(page)
    },
    // 删除数据列
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除数据列'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delColumn(id).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.getColumnList(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    }
  },
  mounted () {
    this.getColumnList(1)
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.tableId) {
        this.getColumnList(1)
      }
    }
  }
}
</script>
