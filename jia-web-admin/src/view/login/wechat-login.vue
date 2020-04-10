<template>
  <wxlogin :appid="appid" :scope="scope" :redirect_uri="redirect_uri"></wxlogin>
</template>

<script>
import wxlogin from 'vue-wxlogin'
import { mapActions } from 'vuex'
import { createJsapi } from '@/api/data'

export default {
  components: {
    wxlogin
  },
  data () {
    return {
      appid: '',
      scope: 'snsapi_login',
      redirect_uri: ''
    }
  },
  methods: {
    ...mapActions([
      'handleLogin',
      'getUserInfo'
    ]),
    initvue () {
      this.createWxCode()
    },
    // 创建二维码
    createWxCode () {
      this.$constant.getWorkToken().then((data) => {
        var appid = 'wx9faba829e04f348b'
        var url = window.location.href
        const post_arr = {
          'appid': appid,
          'url': url
        }
        var access_token = data.access_token
        createJsapi(post_arr, access_token).then(res => {
          if (res.data.msg === 'ok') {
            var resArr = res.data.data
            this.appid = resArr.appId
            this.redirect_uri = encodeURI(res.data.url)
          } else { this.$Message.error(res.data.msg) }
        }).catch(ess => {
          this.$Message.error('请联系管理员')
        })
      })
    }
    // 创建二维码end
  },
  mounted () {
    this.initvue()
  }
}
</script>

<style>

</style>
