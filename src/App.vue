<template>
  <div id="app">
    <div v-transfer-dom>
      <loading v-model="isLoading"></loading>
    </div>
    <div v-transfer-dom>
      <actionsheet :menus="menus" v-model="showMenu" @on-click-menu="onClickMenu"></actionsheet>
    </div>
    <view-box ref="viewBox" body-padding-top="46px" body-padding-bottom="0px">
      <x-header
        :left-options="leftOptions"
        :right-options="rightOptions"
        slot="header"
        style="width:100%;position:absolute;left:0;top:0;z-index:100;"
        :title="title"
        @on-click-more="onClickMore">
      </x-header>
      <router-view/>
    </view-box>
  </div>
</template>

<script>
import { ViewBox, XHeader, Loading, TransferDom, Actionsheet } from 'vux'
export default {
  name: 'App',
  methods: {
    onClickMore () {
      this.showMenu = true
    },
    onClickMenu (key, item) {
      // console.log(key)
      this.$store.commit('global/toMenu', {key: key, event: this})
    }
  },
  computed: {
    isLoading () {
      return this.$store.state.util.isLoading
    },
    leftOptions () {
      return {
        showBack: this.$store.state.global.showBack
      }
    },
    rightOptions () {
      return {
        showMore: this.$store.state.global.showMore
      }
    },
    menus () {
      return this.$store.state.global.menu
    },
    title () {
      return this.$store.state.global.title
    }
  },
  data () {
    return {
      showMenu: false
    }
  },
  directives: {
    TransferDom
  },
  components: {
    ViewBox,
    XHeader,
    Actionsheet,
    Loading
  }
}
</script>

<style lang="less">
@import '~vux/src/styles/reset.less';
@import '~vux/src/styles/1px.less';
@import '~vux/src/styles/close.less';
html, body {
  height: 100%;
  width: 100%;
  margin: 0px;
  overflow-x: hidden;
}
#app {
  height: 100%;
  font-family: 'Avenir', Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: #2c3e50;
}
</style>
