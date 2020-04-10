<template>
  <Layout style="height: 100%" class="main">
    <Sider hide-trigger collapsible :width="256" :collapsed-width="64" v-model="collapsed" class="left-sider" v-show="showMenu" :style="{overflow: 'hidden'}">
      <side-menu accordion ref="sideMenu" :active-name="$route.name" :collapsed="collapsed" @on-select="turnToPage" :menu-list="menuArr">
        <!-- 需要放在菜单上面的内容，如Logo，写在side-menu标签内部，如下 -->
        <!--<div class="logo-title">-->
          <!--<div class="max-div" v-show="!collapsed">-->
            <!--<p >Jiā顺</p>-->
          <!--</div>-->
          <!--<div class="min-div"  v-show="collapsed">-->
            <!--<p>J</p>-->
          <!--</div>-->
        <!--</div>-->
        <div class="logo-con">
          <img v-show="!collapsed" :src="orgLogo" key="max-logo" />
          <img v-show="collapsed" :src="orgLogoIcon" key="min-logo" class="min-logo"/>
          <div v-show="!collapsed" class="menu_name">
            <h2>{{menuName}}</h2>
          </div>
        </div>
      </side-menu>
    </Sider>
    <Layout>
      <Header class="header-con">
        <header-bar ref="HeaderBar" :collapsed="collapsed" :showIcon="showMenu" @on-coll-change="handleCollapsedChange">
          <user :message-unread-count="unreadCount" :user-avator="userAvator" :user-name="userName"/>
          <!--<language v-if="$config.useI18n" @on-lang-change="setLocal" style="margin-right: 10px;" :lang="local"/>-->
          <!--<error-store v-if="$config.plugin['error-store'] && $config.plugin['error-store'].showInHeader" :has-read="hasReadErrorPage" :count="errorCount"></error-store>-->
          <org style="margin-right: 10px;"/>
          <fullscreen v-model="isFullscreen" style="margin-right: 10px;"/>
        </header-bar>
      </Header>
      <Content class="main-content-con">
        <Layout class="main-layout-con">
          <div class="tag-nav-wrapper"  v-show="showTag">
            <tags-nav  :value="$route" @input="handleClick" :list="tagNavList" @on-close="handleCloseTag"/>
          </div>
          <Content class="content-wrapper">
            <keep-alive :include="cacheList">
              <router-view/>
            </keep-alive>
            <ABackTop :height="100" :bottom="80" :right="50" container=".content-wrapper"></ABackTop>
          </Content>
        </Layout>
      </Content>
    </Layout>
  </Layout>
</template>
<script>
import SideMenu from './components/side-menu'
import HeaderBar from './components/header-bar'
import TagsNav from './components/tags-nav'
import Org from './components/org'
import User from './components/user'
import ABackTop from './components/a-back-top'
import Fullscreen from './components/fullscreen'
import Language from './components/language'
import ErrorStore from './components/error-store'
import { mapMutations, mapActions, mapGetters } from 'vuex'
import { getNewTagList, getNextRoute, routeEqual } from '@/libs/util'
import routers from '@/router/routers'
import minLogo from '@/assets/images/logo-min.png'
import maxLogo from '@/assets/images/logo.jpg'
import noAvatar from '@/assets/images/avatar.jpg'
import { getSmsConfig, getCmsConfig } from '@/api/data'
import './main.less'
export default {
  name: 'Main',
  components: {
    SideMenu,
    HeaderBar,
    Language,
    TagsNav,
    Fullscreen,
    ErrorStore,
    Org,
    User,
    ABackTop
  },
  data () {
    return {
      collapsed: false,
      minLogo,
      maxLogo,
      noAvatar,
      isFullscreen: false,
      showMenu: true,
      menuArr: [],
      showTag: true,
      menuName: ''
    }
  },
  computed: {
    ...mapGetters([
      'errorCount'
    ]),
    tagNavList () {
      return this.$store.state.app.tagNavList
    },
    tagRouter () {
      return this.$store.state.app.tagRouter
    },
    userName () {
      return this.$store.state.user.userName
    },
    orgLogo () {
      var orgLogo = this.$store.state.user.orgDate.logo
      if (orgLogo) {
        orgLogo = this.$constant.StaticUrl + orgLogo
      } else {
        orgLogo = this.maxLogo
      }
      return orgLogo
    },
    orgLogoIcon () {
      var orgLogoIcon = this.$store.state.user.orgDate.logoIcon
      if (orgLogoIcon) {
        orgLogoIcon = this.$constant.StaticUrl + orgLogoIcon
      } else {
        orgLogoIcon = this.minLogo
      }
      return orgLogoIcon
    },
    userAvator () {
      var avatar = this.$store.state.user.avatar
      if (avatar) {
        avatar = this.$constant.StaticUrl + avatar
      } else {
        avatar = this.noAvatar
      }
      return avatar
    },
    cacheList () {
      return ['ParentView', ...this.tagNavList.length ? this.tagNavList.filter(item => !(item.meta && item.meta.notCache)).map(item => item.name) : []]
    },
    menuList () {
      return this.$store.getters.menuList
    },
    local () {
      return this.$store.state.app.local
    },
    hasReadErrorPage () {
      return this.$store.state.app.hasReadErrorPage
    },
    unreadCount () {
      return this.$store.state.user.unreadCount
    }
  },
  methods: {
    ...mapMutations([
      'setBreadCrumb',
      'setTagNavList',
      'addTag',
      'setLocal',
      'setHomeRoute',
      'closeTag'
    ]),
    ...mapActions([
      'handleLogin',
      'getUnreadMessageCount'
    ]),
    turnToPage (route) {
      let { path, name, params, query } = {}
      if (typeof route === 'string') name = route
      else {
        path = route.path
        name = route.name
        params = route.params
        query = route.query
      }
      if (name.indexOf('isTurnByHref_') > -1) {
        window.open(name.split('_')[1])
        return
      }
      this.$router.push({
        path,
        name,
        params,
        query
      })
    },
    handleCollapsedChange (state) {
      this.collapsed = state
    },
    handleCloseTag (res, type, route) {
      if (type === 'all') {
        this.turnToPage(this.$config.homeName)
      } else if (routeEqual(this.$route, route)) {
        if (type !== 'others') {
          const nextRoute = getNextRoute(this.tagNavList, route)
          this.$router.push(nextRoute)
        }
      }
      // this.closeTag(route)
      this.setTagNavList(res)
    },
    handleClick (item) {
      this.turnToPage(item)
    },
    async initView (data) {
      var rName = data.name
      if (rName === 'home') {
        this.$refs.HeaderBar.$refs.customBreadCrumb.showLogo = true
        this.showTag = false
        this.showMenu = false
      } else {
        this.$refs.HeaderBar.$refs.customBreadCrumb.showLogo = false
        this.showTag = true
        this.showMenu = true
        var rArr = []
        var routerArr = this.$router.options.routes
        var hasAccess = this.$store.state.user.access
        if (!hasAccess) { window.location.reload() } else {}
        // 将路由拉直
        routerArr.forEach(function (val, i) {
          rArr.push(val)
          var children = val.hasOwnProperty('children')
          if (children) {
            var chiArr = val.children
            chiArr.forEach(function (value, index) {
              rArr.push(value)
            })
          } else {}
        })
        // 获取对应类型的菜单
        var menuArr = []
        // this.menuArr.splice(0, this.menuArr.length)
        var rPid = data.meta.pid
        var menuName = this.$constant.getvalue('MenuArr', rPid)
        this.menuName = menuName
        // var _this = this
        var SmsData, hasSmsData, CmsData, hasCmsData
        if (rPid === 'sms') {
          SmsData = await getSmsConfig()
          hasSmsData = SmsData.data.hasOwnProperty('data')
        } else if (rPid === 'cms') {
          CmsData = await getCmsConfig()
          hasCmsData = CmsData.data.hasOwnProperty('data')
        } else {}
        rArr.forEach(function (v, n) {
          var vPid = v.meta.pid
          var name = v.name
          var meta = v.meta
          var icon = v.meta.icon
          var children = []
          var obj = { 'children': children, 'icon': icon, 'meta': meta, 'name': name }
          if (rPid === vPid) {
            var hideInMenu = v.meta.hasOwnProperty('hideInMenu')
            if (hideInMenu) {
              if (rPid === 'serverDetailed') {
                menuArr.push(obj)
              } else {}
            } else {
              // 判断是否需要判断第一次使用
              if (rPid === 'sms') {
                if (hasSmsData) {
                  menuArr.push(obj)
                } else {
                  if (name === 'sms_config') {
                    menuArr.push(obj)
                  } else {}
                }
              } else if (rPid === 'cms') {
                if (hasCmsData) {
                  menuArr.push(obj)
                } else {
                  if (name === 'cms_config') {
                    menuArr.push(obj)
                  } else {}
                }
              } else if (rPid === 'car') {
                if (hasCmsData) {
                  menuArr.push(obj)
                } else {
                  if (name === 'car_system') {
                    menuArr.push(obj)
                  } else {}
                }
              } else { menuArr.push(obj) }
            }
          } else {}
        })
        for (var i = menuArr.length - 1; i > -1; i--) {
          var access = menuArr[i].meta.hasOwnProperty('access')
          if (access) {
            var CanPush
            var RoutherAccessArr = menuArr[i].meta.access
            RoutherAccessArr.forEach((v, index) => {
              var RoutherAccess = v
              // 循环用户权限
              hasAccess.forEach((value, n) => {
                var UserAccess = value
                if (RoutherAccess === UserAccess) {
                  CanPush = true
                } else {}
              })
            })
            if (CanPush) {} else {
              menuArr.splice(i, 1)
            }
          } else {}
        }
        this.menuArr = menuArr
        this.$nextTick(() => {
          this.$refs.sideMenu.updateOpenName(rName)
          this.$refs.sideMenu.updateActiveName(rName)
        })
      }
    }
  },
  watch: {
    '$route' (newRoute) {
      const { path, name, query, params, meta } = newRoute
      this.addTag({
        route: { path, name, query, params, meta },
        type: 'push'
      })
      this.setBreadCrumb(newRoute)
      this.setTagNavList(getNewTagList(this.tagNavList, newRoute))
      this.initView(newRoute)
    }
  },
  mounted () {
    this.initView(this.$route)
    /**
     * @description 初始化设置面包屑导航和标签导航
     */
    this.setTagNavList()
    this.setHomeRoute(routers)
    this.addTag({
      route: this.$store.state.app.homeRoute
    })
    this.setBreadCrumb(this.$route)
    // 设置初始语言
    this.setLocal(this.$i18n.locale)
    // 如果当前打开页面不在标签栏中，跳到homeName页
    if (!this.tagNavList.find(item => item.name === this.$route.name)) {
      this.$router.push({
        name: this.$config.homeName
      })
    }
    // 获取未读消息条数
    this.getUnreadMessageCount()
  }
}
</script>
<style scoped>
  /*.logo-title{*/
    /*padding: 20px;*/
  /*}*/
  /*.max-div{*/
     /*background-color: #f6f6f6;*/
     /*font-size: 26px;*/
     /*font-weight: 600;*/
     /*text-align: center;*/
     /*border-radius: 15px;*/
   /*}*/
  /*.max-div p{*/
    /*-webkit-mask: linear-gradient(to left, #545353, transparent );*/
  /*}*/
  /*.min-div{*/
    /*background-color: #f6f6f6;*/
    /*font-size: 26px;*/
    /*font-weight: 600;*/
    /*text-align: center;*/
    /*border-radius: 15px;*/
  /*}*/
  /*.min-div p{*/
    /*-webkit-mask: linear-gradient(to left, #545353, transparent );*/
  /*}*/
  .menu_name{
    margin: 20px;
    color: rgba(255, 255, 255, 0.7);
    width: fit-content;
  }
  .min-logo{
    max-width: -webkit-fill-available;
  }
</style>
