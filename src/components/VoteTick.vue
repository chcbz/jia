<template>
  <div>
    <div style="margin:0px 25px; min-height:200px; text-align: center; display: flex;">
      <h2 style="align-self: center;">{{question.title}}</h2>
    </div>
    <a style="float: right; margin: 25px; color: #576b95;" :href="$store.state.global.copyrightLink">{{ $store.state.global.copyright }}</a>
    <span style="margin: 25px; color: #999; display: inline-block;" v-html="$t('vote.pub_author', {'totalNum': totalNum, 'rightNum': rightNum})"></span>
    <box gap="10px 10px">
      <x-button style="text-align:left;" :text="opts[i] + '.' + item.content" v-for="(item, i) in question.items" v-bind:key="item.opt" @click.native="toTick(item.opt)"></x-button>
    </box>
  </div>
</template>

<script>
import { AlertModule, XButton, Box } from 'vux'

export default {
  created: function () {
    this.$store.commit('global/setTitle', this.$t('vote.title'))
    this.$store.commit('global/setShowBack', false)
    this.$store.commit('global/setShowMore', false)
    var baseUrl = this.$store.state.api.baseUrl
    var jiacn = this.$store.state.global.user.jiacn
    var token = this.$store.state.api.token()
    const _this = this
    this.$http.get(baseUrl + '/vote/get/random', {
      params: {
        jiacn: jiacn,
        access_token: token
      }
    }).then(res => {
      _this.question = res.data.data
      for (var i = 0; i < _this.question.items.length; i++) {
        _this.totalNum += _this.question.items[i].num
        if (_this.question.items[i].tick === 1) {
          _this.rightNum = _this.question.items[i].num
        }
      }
    }).catch(error => {
      if (error.response.status === 401) {
        _this.$store.commit('cleanToken')
        _this.$router.go(0)
      }
    })
  },
  methods: {
    onClickOpMenu: function (key, item) {
      console.log(item)
    },
    toTick: function (opt) {
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      const _this = this
      this.$http.post(baseUrl + '/vote/tick', {
        jiacn: jiacn,
        questionId: this.question.id,
        opt: opt
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        if (res.data.data) {
          AlertModule.show({
            title: _this.$t('app.notify'),
            content: _this.$t('vote.right_alert', {'point': _this.question.point}),
            onHide () {
              _this.$router.go(0)
            }
          })
        } else {
          AlertModule.show({
            title: _this.$t('app.alert'),
            content: _this.$t('vote.wrong_alert', {'opt': _this.question.opt})
          })
        }
      }).catch(error => {
        if (error.response.status === 401) {
          _this.$store.commit('cleanToken')
          _this.$router.go(0)
        }
      })
    }
  },
  data () {
    return {
      question: {},
      showOpMenu: false,
      opts: ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'],
      totalNum: 0,
      rightNum: 0
    }
  },
  components: {
    AlertModule,
    XButton,
    Box
  }
}
</script>
<style lang="less" scoped>
@import "~vux/src/styles/1px.less";
@import "~vux/src/styles/center.less";
</style>
