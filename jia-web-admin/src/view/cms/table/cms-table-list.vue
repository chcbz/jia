<template>
  <div>
        <Card>
          <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
            <div  class="search_div">
              <div  class="search_chil_div">
                <div>
                  <FormItem label="表格名称">
                    <Input v-model="searchList.name"></Input>
                  </FormItem>
                </div>
              </div>
            </div>
          </Form>
          <div class="search_operate">
            <Button  @click="searchTable(1)">查询</Button>
            <Button style="margin: 10px 10px;" type="success" @click="handleCreate">创建表格</Button>
          </div>
          <tables ref="tables" editable  border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
        </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <table-info ref="TableInfo" @operate="ParentOperate"></table-info>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import TableInfo from './modal/table-info'
import { CmsTableList, createCmsTable, delCmsTable } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    TableInfo
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        name: ''
      },
      columns: [
        { title: '表格名称',
          key: 'name'
        },
        { title: '表格中文名',
          key: 'remark'
        },
        { title: 'clientId',
          key: 'clientId'
        },
        {
          title: '操作',
          key: 'operate',
          width: 140,
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
                  size: 'small'
                },
                on: {
                  click: () => {
                    var tableId = data.id
                    var name = data.name
                    this.$router.push({ path: 'cms_table_column_list/' + tableId, query: { name: name } })
                  }
                }
              }, '详细'),
              // h('Button', {
              //   props: {
              //     type: 'info',
              //     size: 'small'
              //   },
              //   on: {
              //     click: () => {
              //       this.handleUpdate(data)
              //     }
              //   }
              // }, '修改'),
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
    // 创建表格
    handleCreate () {
      this.$refs.TableInfo.$refs.TableInfo_form.resetFields()
      this.$refs.TableInfo.$refs.colFormList.resetFields()
      this.$refs.TableInfo.TableInfo_title = '创建表格'
      this.$refs.TableInfo.TableInfo_judge = 'create'
      this.$refs.TableInfo.TableInfo_form.name = ''
      this.$refs.TableInfo.TableInfo_form.remark = ''
      var arr = this.$refs.TableInfo.colFormList.items
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
      this.$refs.TableInfo.TableInfo_modal = true
    },
    // 修改表格
    // handleUpdate (x) {
    //   this.$refs.TableInfo.$refs.TableInfo_form.resetFields()
    //   this.$refs.TableInfo.TableInfo_title = '修改表格'
    //   this.$refs.TableInfo.TableInfo_modal = true
    //   this.$refs.TableInfo.TableInfo_id = x.id
    //   this.$refs.TableInfo.TableInfo_form.token = x.token
    //   this.$refs.TableInfo.TableInfo_form.encodingaeskey = x.encodingaeskey
    //   this.$refs.TableInfo.TableInfo_form.level = (x.level).toString()
    //   this.$refs.TableInfo.TableInfo_form.name = x.name
    //   this.$refs.TableInfo.TableInfo_form.account = x.account
    //   this.$refs.TableInfo.TableInfo_form.original = x.original
    //   this.$refs.TableInfo.TableInfo_form.signature = x.signature
    //   this.$refs.TableInfo.TableInfo_form.country = x.country
    //   this.$refs.TableInfo.TableInfo_form.province = x.province
    //   this.$refs.TableInfo.TableInfo_judge = 'update'
    // },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.TableInfo.TableInfo_judge
      var name = this.$refs.TableInfo.TableInfo_form.name
      var remark = this.$refs.TableInfo.TableInfo_form.remark
      var arr = this.$refs.TableInfo.colFormList.items
      arr.forEach(function (val, i) {
        arr[i].scale = (val.type !== 'double') ? null : val.scale
        arr[i].defaultValue = (val.defaultValue === '') ? null : val.defaultValue
        delete arr[i].index
      })
      var columns = arr
      var data = {
        'name': name,
        'remark': remark,
        'columns': columns
      }
      if (judge === 'create') {
        // 大表单
        this.$refs.TableInfo.$refs.TableInfo_form.validate((valid) => {
          if (valid) {
            // 子表单
            this.$refs.TableInfo.$refs.colFormList.validate((valid) => {
              if (valid) {
                this.$Spin.show()
                createCmsTable(data).then(res => {
                  if (res.data.msg === 'ok') {
                    this.$Spin.hide()
                    this.$Message.success('成功创建')
                    this.$refs.TableInfo.TableInfo_modal = false
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
          } else {
            return false
          }
        })
      } else {}
    },
    // 删除表格
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除已记录的表格'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delCmsTable(id).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.searchTable(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getCmsTableList(page, obj)
    },
    getCmsTableList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      var data = {
        'pageNum': x,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      CmsTableList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    }
  },
  mounted () {
    this.searchTable(1)
  }
}
</script>
