<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="状态">
                <Select v-model="searchList.status" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in SmsBuyStatus" :value="option.id" :key="v">{{option.str}}</Option>
                </Select>
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
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import Tables from '_c/tables'
import { SmsBuyList, SmsCancelBuy, SmsBuyPay } from '@/api/data'
import Ready from '_c/modal/ready'

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
        status: ''
      },
      SmsBuyStatus: [],
      columns: [
        { title: '短信数量', key: 'number' },
        { title: '价格', key: 'money' },
        { title: '状态',
          render: (h, params) => {
            var data = params.row
            var status = data.status
            var result = this.$constant.getvalue('SmsBuyStatus', status)
            return h('span', result)
          }
        },
        { title: '创建时间',
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
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            var status = (data.status === 0)
            var pay = h('Button', {
              props: {
                type: 'info',
                size: 'small',
                disabled: !status
              },
              on: {
                click: () => {
                  this.handlePay(data)
                }
              }
            }, '支付')
            var cancelPay = h('Button', {
              props: {
                type: 'error',
                size: 'small',
                disabled: !status
              },
              on: {
                click: () => {
                  this.cancelPay(data)
                }
              }
            }, '取消支付')
            var result
            result = h('div', {
              class: {
                table_operate: true
              }
            }, [pay, cancelPay])
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
      this.getSmsBuyList(page, JSON.stringify(obj))
    },
    // 初始化
    initView () {
      this.SmsBuyStatus = this.$constant.SmsBuyStatus
    },
    // 支付跳转
    handlePay (arr) {
      var buyId = arr.id
      SmsBuyPay(buyId).then(res => {
        if (res.data.msg === 'ok') {
          var productId = res.data.data
          this.$router.push({ path: '/wechat/wechat_scanPay/' + productId, query: { productId: productId, buyId: buyId } })
        } else { this.$Message.error(res.data.msg) }
      })
    },
    // 获取短信模板
    getSmsBuyList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      const data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      SmsBuyList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    },
    // 取消支付
    cancelPay (x) {
      var data = x
      this.$refs.ready.ready_title = '取消购买'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否取消购买该套餐'
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      SmsCancelBuy(id).then(res => {
        this.$Spin.hide()
        this.searchTable(1)
        this.$Message.success('取消订单成功')
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    }
  },
  mounted () {
    this.initView()
    this.searchTable(1)
  }
}
</script>

<style scoped>

</style>
