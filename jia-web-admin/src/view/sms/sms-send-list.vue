<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="关键词">
                <Input v-model="searchList.mobile"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
      </div>
      <tables ref="tables"  border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
  </div>
</template>
<script>
import Tables from '_c/tables'
import { getSmsSend } from '@/api/data'

export default {
  components: {
    Tables
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        mobile: ''
      },
      columns: [
        { title: '电话号码',
          width: 140,
          key: 'mobile'
        },
        { title: '内容',
          key: 'content'
        },
        { title: '时间',
          key: 'time',
          sortable: true,
          render: (h, params) => {
            var data = params.row
            var time = data.time
            var result = this.$constant.formatDateTime(time)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          width: 200,
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
                    var msgid = data.msgid
                    this.$router.push({ path: 'sms_reply_list/' + msgid })
                  }
                }
              }, '查看所有回复信息')
            ])
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
      this.SmsSendList(page, JSON.stringify(obj))
    },
    SmsSendList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      getSmsSend(data).then(res => {
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
