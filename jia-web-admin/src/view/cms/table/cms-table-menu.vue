<template>
  <div>
    <Card>
      <div class="tofu_wrap">
      <Card v-for="item in nameArr" :key="item.id" class="tofu" @click.native="tableUrl(item.id,item.name)">
        <!-- <p class="table_name">{{item.name}}</p> -->
        <p class="table_remark">{{item.remark}}</p>
      </Card>
      </div>
    </Card>
  </div>
</template>
<script>
import { CmsTableList } from '@/api/data'

export default {
  components: {
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 999,
      nameArr: []
    }
  },
  methods: {
    tableUrl (x, y) {
      var tableId = x
      var name = y
      this.$router.push({ path: 'cms_table_row_list/' + name, query: { tableId: tableId } })
    },
    getCmsTableList () {
      const pageNum = this.pageNum
      const pageSize = this.pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      var Narr = []
      CmsTableList(data).then(res => {
        var arr = res.data.data
        arr.forEach(function (val, i) {
          Narr.push(val)
        })
        this.nameArr = Narr
      })
    }
  },
  mounted () {
    this.getCmsTableList()
  }
}
</script>

<style lang="less" scoped>
 /deep/ .tofu .ivu-card-body{
   margin-top: 22px;
 }
  .table_name{
    font-weight: 600;
    font-size: 20px;
  }
 .table_remark{
   font-weight: 300;
   font-size: 16px;
 }
</style>
