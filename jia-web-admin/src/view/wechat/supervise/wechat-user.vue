<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
      <div  class="search_div">
      <div  class="search_chil_div">
      <div>
      <FormItem label="jia账户">
      <Input v-model="searchList.jiacn"></Input>
      </FormItem>
        <FormItem label="邮箱">
          <Input v-model="searchList.email"></Input>
        </FormItem>
        <FormItem label="性别">
          <Select v-model="searchList.sex" class="search_select" clearable filterable>
            <Option v-for="(option, v) in WxUserSex" :value="option.id" :key="v">{{option.str}}</Option>
          </Select>
        </FormItem>
      </div>
      </div>
      </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 10px;" type="success" @click="handleSync">同步用户到系统</Button>
      </div>
      <tables ref="tables" editable border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
  </div>
</template>
<script>
import Tables from '_c/tables'
import { syncWxMpUser, WxMpUserList } from '@/api/data'

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
        appid: '',
        jiacn: '',
        email: '',
        sex: ''
      },
      WxUserSex: [],
      columns: [
        { title: '头像',
          width: 120,
          key: 'headImgUrl',
          render: (h, params) => {
            var data = params.row
            var headImgUrl = data.headImgUrl
            var obj = h('img', {
              style: {
                padding: '2px',
                width: '90px',
                height: 'auto'
              },
              attrs: {
                src: headImgUrl
              }
            }, '')
            return obj
          }
        },
        { title: 'jia账户', key: 'jiacn' },
        { title: '姓名', key: 'nickname' },
        { title: '性别',
          // width: '80px',
          key: 'sex',
          render: (h, params) => {
            var data = params.row
            var sex = data.sex
            var result = this.$constant.getvalue('WxUserSex', sex)
            return h('span', result)
          }
        },
        // { title: '邮箱', key: 'email' },
        { title: '城市', width: '120px', key: 'city' }
      ],
      tableData: []
    }
  },
  methods: {
    // 同步用户
    handleSync () {
      var appid = this.searchList.appid
      this.$Spin.show()
      syncWxMpUser(appid).then(res => {
        this.$Spin.hide()
        if (res.data.msg === 'ok') {
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功同步')
          this.searchTable(1)
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.WxMpUserList(page, JSON.stringify(obj))
    },
    // 初始化
    initView () {
      this.WxUserSex = this.$constant.WxUserSex
      this.searchList.appid = this.$route.params.appid
      this.searchTable(1)
    },
    // 获取用户模板
    WxMpUserList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      // for (var key in z) {
      //   data[key] = z[key].trim()
      // }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      WxMpUserList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.WxMpUserList(page)
    }
  },
  mounted () {
    this.initView()
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.appid) {
        this.searchList.appid = this.$route.params.appid
        this.searchTable(1)
      }
    }
  }
}
</script>

<style scoped>

</style>
