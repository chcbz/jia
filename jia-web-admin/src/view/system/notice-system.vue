<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="标题">
                <Input v-model="searchList.title"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 10px;" type="success" @click="handleCreate">创建新公告</Button>
      </div>
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import { delNotice, getNoticeList } from '@/api/data'

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
        title: ''
      },
      columns: [
        { title: '标题', key: 'title' },
        // {
        //   title: '内容',
        //   key: 'content',
        //   render: (h, params) => {
        //     var data = params.row
        //     var content = data.content
        //     return h('span', {
        //       class: {
        //         show_line: true
        //       }
        //     }, content)
        //   }
        // },
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
            var update = h('Button', {
              props: {
                type: 'info',
                size: 'small'
              },
              on: {
                click: () => {
                  var id = data.id
                  this.$router.push({ path: 'notice_editor/' + id })
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
                  this.handleDel(data)
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
    // 创建公告
    handleCreate () {
      this.$router.push({ path: 'notice_editor/' + 0 })
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.NoticeList(page, JSON.stringify(obj))
    },
    // 初始化
    initView () {
      this.searchTable(1)
    },
    // 获取公告模板
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
      getNoticeList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 删除公告模板
    handleDel (x) {
      var data = x
      var name = data.title
      this.$refs.ready.ready_title = '删除公告模板'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除公告模板-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delNotice(id).then(res => {
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

</style>
