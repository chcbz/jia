<template>
<div>
  <div v-transfer-dom>
    <actionsheet :menus="opMenu" v-model="showOpMenu" @on-click-menu="onClickOpMenu"></actionsheet>
  </div>
  <panel :list="list" type="4" @on-click-item="doShowOpMenu"></panel>
</div>
</template>

<script>
import { Panel, dateFormat, TransferDom, Actionsheet, AlertModule, ConfirmPlugin } from 'vux'

export default {
  created: function () {
    this.$store.commit('global/setTitle', this.$t('gift.order_list'))
    this.$store.commit('global/setShowBack', true)
    this.$store.commit('global/setShowMore', false)
    var baseUrl = this.$store.state.api.baseUrl
    var token = this.$store.state.api.token()
    var jiacn = this.$store.state.global.user.jiacn
    this.$http.post(baseUrl + '/gift/usage/list/user/' + jiacn, {
      search: JSON.stringify({
        pageNum: 1,
        pageSize: 999
      })
    }, {
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json'
      }
    }).then(res => {
      const _this = this
      this.list = []
      res.data.data.forEach(element => {
        let orderItem = {
          id: element.id,
          title: element.name,
          desc: element.description,
          status: element.status,
          meta: {
            source: element.point ? element.point + _this.$t('gift.point') : '￥' + element.price,
            date: dateFormat(this.$store.state.util.fromTimeStamp(element.time), 'YYYY-MM-DD'),
            other: _this.$t('gift.quantity_title', {'quantity': element.quantity}) + '　' + _this.statusMap[element.status]
          }
        }
        this.list.push(orderItem)
      })
    })
  },
  methods: {
    doShowOpMenu: function (item) {
      this.selectId = item.id
      if (item.status === 1) {
        this.opMenu = {'cancel': this.$t('gift.cancel')}
      } else if (item.status === 0 || item.status === 5) {
        this.opMenu = {'del': this.$t('gift.del')}
      }
      this.showOpMenu = true
    },
    onClickOpMenu: function (key, item) {
      if (key === 'del') {
        const _this = this
        this.$vux.confirm.show({
          title: _this.$t('gift.del_alert'),
          onConfirm () {
            var baseUrl = _this.$store.state.api.baseUrl
            var token = _this.$store.state.api.token()
            _this.$http.post(baseUrl + '/gift/usage/delete/' + _this.selectId, {

            }, {
              headers: {
                Authorization: 'Bearer ' + token,
                'Content-Type': 'application/json'
              }
            }).then(res => {
              if (res.data.code === 'E0') {
                AlertModule.show({
                  title: _this.$t('app.notify'),
                  content: res.data.msg,
                  onHide () {
                    _this.$router.go(0)
                  }
                })
              } else {
                AlertModule.show({title: _this.$t('app.alert'), content: res.data.msg})
              }
            })
          }
        })
      } else if (key === 'cancel') {
        const _this = this
        this.$vux.confirm.show({
          title: _this.$t('gift.cancel_alert'),
          onConfirm () {
            var baseUrl = _this.$store.state.api.baseUrl
            var token = _this.$store.state.api.token()
            _this.$http.post(baseUrl + '/gift/usage/cancel/' + _this.selectId, {

            },
            {
              headers: {
                Authorization: 'Bearer ' + token,
                'Content-Type': 'application/json'
              }
            }).then(res => {
              if (res.data.code === 'E0') {
                AlertModule.show({
                  title: _this.$t('app.notify'),
                  content: res.data.msg,
                  onHide () {
                    _this.$router.go(0)
                  }
                })
              } else {
                AlertModule.show({title: _this.$t('app.alert'), content: res.data.msg})
              }
            })
          }
        })
      }
    }
  },
  data () {
    return {
      list: [],
      opMenu: {},
      showOpMenu: false,
      selectId: 0,
      statusMap: {
        '0': '未支付',
        '1': '已支付',
        '5': '已取消'
      }
    }
  },
  directives: {
    TransferDom
  },
  components: {
    Panel,
    dateFormat,
    Actionsheet,
    ConfirmPlugin
  }
}
</script>
