<template>
  <div>
    <Card>
      <!--<Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">-->
        <!--<div  class="search_div">-->
          <!--<div  class="search_chil_div">-->
            <!--<div>-->
              <!--<FormItem label="标题">-->
                <!--<Input v-model="searchList.title"></Input>-->
              <!--</FormItem>-->
            <!--</div>-->
          <!--</div>-->
        <!--</div>-->
      <!--</Form>-->
      <div class="search_operate">
        <!--<Button  @click="searchTable(1)">查询</Button>-->
        <Button style="margin: 10px 10px;" type="success" @click="handleCreate">创建新文章</Button>
      </div>
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <Send-modal ref="SendModal"></Send-modal>
    <Word-modal ref="WordModal"></Word-modal>
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import { delNews, NewsList } from '@/api/data'
import SendModal from './modal/send-template'
import WordModal from './modal/word-export'

export default {
  components: {
    Tables,
    Ready,
    SendModal,
    WordModal
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
        { title: '作者', key: 'author' },
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
            var send = h('Button', {
              props: {
                type: 'info',
                size: 'small'
              },
              on: {
                click: () => {
                  this.ParentSend(data)
                }
              }
            }, '发送')
            var word = h('Button', {
              props: {
                type: 'primary',
                size: 'small'
              },
              on: {
                click: () => {
                  this.ExportWord(data)
                }
              }
            }, '查看与导出')
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
            }, [send, word, del])
            return result
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 导出
    ExportWord (x) {
      this.$refs.WordModal.WordExport_id = x.id
      this.$refs.WordModal.WordExport_modal = true
    },
    // 发送
    ParentSend (x) {
      this.$refs.SendModal.SendTemplate_templateId = x.id
      this.$refs.SendModal.SendTemplate_modal = true
    },
    // 创建文章
    handleCreate () {
      this.$router.push({ path: 'news_editor/' + 0 })
    },
    // // 查找数据
    // searchTable (page) {
    //   var s_obj = this.searchList
    //   var obj = this.$constant.appendSearch(s_obj)
    //   this.NewsList(page, JSON.stringify(obj))
    // },
    // 初始化
    initView () {
      this.NewsList(1)
    },
    // 获取文章模板
    NewsList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize
        // 'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      NewsList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.NewsList(page)
    },
    // 删除文章模板
    handleDel (x) {
      var data = x
      var name = data.title
      this.$refs.ready.ready_title = '删除文章'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除文章-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delNews(id).then(res => {
        this.$Spin.hide()
        if (res.data.msg === 'ok') {
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功删除')
          this.NewsList(1)
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
