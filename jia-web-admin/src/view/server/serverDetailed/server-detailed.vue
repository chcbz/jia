<template>
  <div>
    <Card>
      <p slot="title">
        已安装的服务
      </p>
      <div class="tofu_wrap">
        <Card v-for="item in haveServerArr"  :key="item.id" class="tofu">
          <p class="table_name">{{item.name}}</p>
          <p v-if="item.status>0">
            <Tag color="success">{{$constant.getvalue('ServerAppStatus', parseInt(item.status))}}</Tag>
          </p>
          <p v-else>
            <Tag color="warning">{{$constant.getvalue('ServerAppStatus', parseInt(item.status))}}</Tag>
          </p>
        </Card>
      </div>
    </Card>
    <!--<ready ref="ready" @Next="ParentNext"></ready>-->
  </div>
</template>
<script>
// import Ready from '_c/modal/ready'
import { haveServerList } from '@/api/data'

export default {
  components: {
    // Ready
  },
  data () {
    return {
      serverId: '',
      haveServerArr: []
    }
  },
  methods: {
    // 已安装服务
    haveServer () {
      const serverId = this.serverId
      var data = {
        'serverId': serverId
      }
      haveServerList(data).then(res => {
        var result = res.data.data
        var arr = []
        result.forEach((val, i) => {
          var status = val.status
          if (status >= 0) {
            arr.push(val)
          } else {}
        })
        this.haveServerArr = arr
      })
    },
    // 获取ServerId
    getServerId () {
      var w_url = window.location.href // 获取当前页面url
      var arr = w_url.split('/')
      var serverId = arr[arr.length - 1] // 地址栏链接页面
      this.serverId = parseInt(serverId)
    }
  },
  computed: {},
  mounted () {
    this.haveServer()
  },
  created () {
    this.getServerId()
  },
  watch: {
    '$route' (to, from) {
      this.getServerId()
      this.haveServer()
    }
  }
}
</script>

<style lang="less" scoped>
  /deep/ .tofu{
    text-align: center;
  }
  /deep/ .tofu .ivu-card-body{
    margin-top: 22px;
  }
  .table_name{
    font-weight: 600;
    font-size: 20px;
  }
</style>
