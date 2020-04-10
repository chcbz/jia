<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="文件名">
                <Input v-model="searchList.name"></Input>
              </FormItem>
              <FormItem label="文件类型">
                <Select v-model="searchList.type" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in FileType" :value="option.id" :key="v">{{option.str}}</Option>
                </Select>
              </FormItem>
              <FormItem label="文件后缀">
                <Input v-model="searchList.extension"></Input>
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
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import { delFile, FileList } from '@/api/data'

export default {
  components: {
    Tables,
    Ready
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        name: '',
        type: '',
        extension: ''
      },
      FileType: [],
      columns: [
        {
          title: '文件名',
          key: 'name',
          // className: 'show_second',
          render: (h, params) => {
            var data = params.row
            var name = data.name
            var url = this.$constant.StaticUrl + data.uri
            // var obj = h('img', {
            //   style: {
            //     padding: '2px',
            //     width: '90px',
            //     height: 'auto'
            //   },
            //   attrs: {
            //     src: url
            //   }
            // }, '')
            // return obj
            return h('Tooltip', {
              style: {
                color: '#00b5ff'
              },
              class: {
                show_d: true
              },
              props: {
                placement: 'top',
                transfer: true
              }
            }, [
              name,
              h('div', {
                slot: 'content',
                style: {
                  textAlign: 'center'
                }
              }, [ h('img', {
                style: {
                  width: '80%',
                  height: 'auto'
                },
                attrs: {
                  src: url
                }
              }, '')])
            ])
          }
        },
        {
          title: '文件大小',
          key: 'size',
          render: (h, params) => {
            var data = params.row
            var size = data.size
            var result = (size / 1024).toFixed(2) + 'KB'
            return h('span', result)
          }
        },
        {
          title: '文件类型',
          key: 'type',
          render: (h, params) => {
            var data = params.row
            var type = data.type
            var result = this.$constant.getvalue('FileType', type)
            return h('span', result)
          }
        },
        { title: '文件后缀', key: 'extension' },
        {
          title: '创建时间',
          key: 'createTime',
          sortable: true,
          render: (h, params) => {
            var data = params.row
            var createTime = data.createTime
            var result = this.$constant.formatDateTime(createTime)
            return h('span', result)
          }
        },
        {
          title: '最新更新时间',
          key: 'updateTime',
          sortable: true,
          render: (h, params) => {
            var data = params.row
            var updateTime = data.updateTime
            var result = this.$constant.formatDateTime(updateTime)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var del = h('Button', {
              props: {
                type: 'error',
                size: 'small'
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
            }, [del])
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
      this.NoticeList(page, JSON.stringify(obj))
    },
    // 初始化
    initView () {
      this.FileType = this.$constant.FileType
      this.searchTable(1)
    },
    // 获取静态文件模板
    NoticeList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      FileList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除静态文件模板
    handleDel (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除静态文件模板'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除静态文件模板-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delFile(id).then(res => {
        this.$Spin.hide()
        if (res.data.msg === 'ok') {
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功删除')
          this.searchTable(1)
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    }
  },
  mounted () {
    this.initView()
  }
}
</script>

<style scoped>
  /deep/.show_d .ivu-tooltip-rel{
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }
</style>
