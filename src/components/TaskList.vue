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
    this.$store.commit('global/setMenu', {
      menus: [{
        key: 'add',
        value: this.$t('task.add'),
        fn: function () {
          this.$router.push({name: 'TaskAdd'})
        }
      }, {
        key: 'history',
        value: this.$t('task.history'),
        fn: function () {
          this.$router.push({name: 'TaskHistory'})
        }
      }],
      event: this
    })
    this.$store.commit('global/setTitle', this.$t('app.task_list'))
    this.$store.commit('global/setShowBack', true)
    var baseUrl = this.$store.state.api.baseUrl
    var token = this.$store.state.api.token()
    var jiacn = this.$store.state.global.user.jiacn
    var now = this.$store.state.util.toTimeStamp(new Date())
    this.$http.post(baseUrl + '/task/search', {
      search: JSON.stringify({
        jiacn: jiacn,
        status: 1,
        endTimeStart: now
      })
    }, {
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json'
      }
    }).then(res => {
      this.list = []
      res.data.data.forEach(element => {
        let taskItem = {
          id: element.id,
          title: element.name,
          desc: element.description,
          meta: {
            source: '￥' + element.amount,
            date: dateFormat(this.$store.state.util.fromTimeStamp(element.startTime), 'YYYY-MM-DD') + ' - ' + dateFormat(this.$store.state.util.fromTimeStamp(element.endTime), 'YYYY-MM-DD'),
            other: element.crond
          }
        }
        this.list.push(taskItem)
      })
    })
  },
  methods: {
    doShowOpMenu: function (item) {
      this.selectId = item.id
      this.showOpMenu = true
    },
    onClickOpMenu: function (key, item) {
      if (key === 'del') {
        const _this = this
        this.$vux.confirm.show({
          title: _this.$t('task.del_alert'),
          onConfirm () {
            var baseUrl = _this.$store.state.api.baseUrl
            var token = _this.$store.state.api.token()
            _this.$http.get(baseUrl + '/task/delete', {
              params: {
                id: _this.selectId
              },
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
          title: _this.$t('task.cancel_alert'),
          onConfirm () {
            var baseUrl = _this.$store.state.api.baseUrl
            var token = _this.$store.state.api.token()
            _this.$http.get(baseUrl + '/task/cancel', {
              params: {
                id: _this.selectId
              },
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
      opMenu: {
        del: this.$t('task.del'),
        cancel: this.$t('task.cancel')
      },
      showOpMenu: false,
      selectId: 0
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
