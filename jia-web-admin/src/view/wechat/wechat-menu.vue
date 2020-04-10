<template>
  <div>
    <Card>
      <div class="tofu_wrap">
      <Card v-for="item in nameArr"  :key="item.id" class="tofu"  v-menus>
        <p class="wechat_name">{{item.name}}</p>
        <p class="wechat_data">{{item}}</p>
      </Card>
      </div>
    </Card>
    <!--菜单栏-->
    <ul  class="wechat_menu"  :style="{'left': menuLeft, 'top': menuTop}" v-show="menuShow" v-appendmenu>
      <li @click="WechatUrl('user')">用户管理</li>
      <li @click="WechatUrl('menu')">菜单管理</li>
      <li @click="WechatUrl('material')">素材管理</li>
    </ul>
  </div>
</template>
<script>
import { WxMpList } from '@/api/data'

export default {
  components: {
  },
  // 自定义事件
  directives: {
    menus: {
      inserted: function (el, binding, vnode) {
        // 获取vue实例对象
        let vm = vnode.context
        // 阻止默认浏览器的右键菜单
        el.oncontextmenu = (event) => {
          event.preventDefault()
        }
        el.onmouseup = (event) => {
          if (event.button === 2) {
            var data = vnode.elm.children['0'].children[1].innerText
            data = data.replace('{', '')
            data = data.replace('}', '')
            var s_data = data.split(',')
            s_data.forEach(function (val, i) {
              var key = val.split(':')[0].replace('"', '').replace('"', '').replace(/\s*/g, '')
              var value = val.split(':')[1].replace('"', '').replace('"', '').replace(/\s*/g, '')
              vm.WechatData[key] = value
            })
            vm.menuShow = true
            vm.menuLeft = event.clientX + 'px'
            vm.menuTop = event.clientY + 'px'
          }
        }
        document.onclick = () => {
          vm.menuShow = false
        }
      }
    },
    appendmenu: {
      inserted: function (el, binding, vnode) {
        // 阻止默认浏览器的右键菜单
        el.oncontextmenu = (event) => {
          event.preventDefault()
        }
      }
    }
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 999,
      nameArr: [],
      // 菜单属性
      menuShow: false,
      menuLeft: 0,
      menuTop: 0,
      // 当前点击的公众号data
      WechatData: []
    }
  },
  methods: {
    WechatUrl (x) {
      var type = x
      var data = this.WechatData
      var appid = data['appid']
      var name = data['name']
      if (type === 'user') {
        this.$router.push(
          { path: 'wechat_user/' + appid, query: { name: name } }
          // this.$Message.error('暂时未开放')
        )
      } else if (type === 'menu') {
        this.$router.push({ path: 'wechat_diymenu/' + appid, query: { name: name } })
      } else {
        // this.$router.push({ path: 'wechat_material/' + appid, query: { name: name } })
        this.$Message.error('暂时未开放')
      }
    },
    getWxMpList () {
      const pageNum = this.pageNum
      const pageSize = this.pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize
      }
      var Narr = []
      WxMpList(data).then(res => {
        var arr = res.data.data
        arr.forEach(function (val, i) {
          Narr.push(val)
        })
        this.nameArr = Narr
      })
    }
  },
  mounted () {
    this.getWxMpList()
  }
}
</script>

<style lang="less" scoped>
 /deep/ .tofu .ivu-card-body{
   margin-top: 22px;
 }
  .wechat_name{
    /* text-shadow: 0 0.1px 0.05rem #949494; */
    color: #7f8790;
    letter-spacing: 0.09em;
    font-size: 15px;
    font-weight: 600;
    text-align: center;
  }
 .wechat_data{
  display: none;
 }
  /*弹出菜单样式*/
  .wechat_menu {
    margin: 0;
    background: #fff;
    width: 100px;
    z-index: 9999;
    position: absolute;
    list-style-type: none;
    padding: 5px 0;
    border-radius: 4px;
    font-size: 12px;
    font-weight: 400;
    color: #333;
    box-shadow: 1px 1px 1px 1px rgba(0, 0, 0, .3)
  }

 .wechat_menu li {
   margin: 0;
   padding: 7px 16px;
   cursor: pointer;
 }

 .wechat_menu li:hover {
   background: #eee;
 }
</style>
