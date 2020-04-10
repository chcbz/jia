<template>
  <div>
    <Card>
      <tables ref="tables" border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
  </div>
</template>
<script>
import Tables from '_c/tables'
import { getSmsReply } from '@/api/data'

export default {
  components: {
    Tables
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      columns: [
        { title: '电话号码',
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
        }
      ],
      tableData: []
    }
  },
  methods: {
    SmsReplyList (x) {
      const pageNum = x
      const pageSize = this.pageSize
      const msgid = this.$route.params.msgid
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': '{"msgid":"' + msgid + '"}'
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      getSmsReply(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.SmsReplyList(page)
    }
  },
  mounted () {
    this.SmsReplyList(1)
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.msgid) {
        this.SmsReplyList(1)
      }
    }
  }
}
</script>
