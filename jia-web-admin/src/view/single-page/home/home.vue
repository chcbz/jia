<template>
  <div>
    <Row>
      <Col span="15">
        <Row style="margin-right: 16px;">
          <Col>
            <div>
              <Card  class="menu_wrap">
                <p slot="title" class="menu_header">服务菜单</p>
                <Row  class="menu_row">
                  <Col  v-for="(item,index) in mainMenuArr"  :key="index"  span="8" class="menu_obj" @click.native="goUrl(item.id)">
                    <div class="menu_div">
                      <div class="menu_icon"><Icon :type="item.icon" /></div>
                      <div class="menu_title"><span>{{item.str}}</span></div>
                    </div>
                  </Col>
                </Row>
              </Card>
            </div>
          </Col>
          <Col class="wrap_bottom">
            <div>
              <Card  class="menu_wrap">
                <p slot="title" class="menu_header">技术文档</p>
                <Row  class="menu_row">
                  <Col  v-for="(item,index) in mainDocMenuArr"  :key="index"  span="8" class="menu_obj" @click.native="goDocUrl(item.url)">
                    <div class="menu_div">
                      <div class="menu_icon"><Icon :type="item.icon" /></div>
                      <div class="menu_title"><span>{{item.str}}</span></div>
                    </div>
                  </Col>
                </Row>
              </Card>
            </div>
          </Col>
        </Row>
      </Col>
      <Col span="9">
        <Row>
          <Col>
            <Card class="tab_wrap">
              <p slot="title" class="menu_header">公告中心</p>
              <Tabs  @on-click="noticeList" class="tabs_wrap">
                <TabPane  v-for="(option, v) in noticeType" :key="v" :label="option.name" :name="option.value">
                  <div class="nlist_wrap" v-if="option.value===noticeKey">
                    <ul>
                      <li v-for="(res, i) in noticeArr" :key="i" >
                        <div class="notice_obj" @click="readContent(res.id)">
                          <span class="notice_title">{{res.title}}</span>
                          <!--<span class="notice_content">{{res.content}}</span>-->
                        </div>
                      </li>
                    </ul>
                  </div>
                </TabPane>
              </Tabs>
              <div class="notice_operate"><span @click="moreNotice">更多</span></div>
            </Card>
          </Col>
          <Col class="wrap_bottom">
            <Card>
              <Row>
                <Col span="12">
                  <img class="qrCode_img" :src="QrCode"/>
                </Col>
                <Col span="12">
                  <h3 class="qrCode_title">Jiā顺</h3>
                  <ul class="qrCode_ul">
                    <li>你+我，家和万事顺</li>
                    <li>你+我，成长加速度</li>
                    <li>你+我，分享嘉能量</li>
                  </ul>
                </Col>
              </Row>
            </Card>
          </Col>
        </Row>
      </Col>
    </Row>
    <clear-modal ref="clearModal" :typeN="typeN" :typeV="typeV"></clear-modal>
  </div>
</template>

<script>
import QrCode from '@/assets/images/jia.jpg'
import { getDictTable, getNoticeList, getSmsConfig, getCmsConfig } from '@/api/data'
import clearModal from '../modal/clear'
// import { localSave } from '@/libs/util'
export default {
  name: 'home',
  components: {
    clearModal
  },
  data () {
    return {
      QrCode,
      mainMenuArr: [],
      mainDocMenuArr: [],
      chiMenuArr: [],
      noticeKey: '',
      noticeType: [],
      noticeArr: [],
      SmsDataHas: '',
      CmsDataHas: '',
      typeN: '',
      typeV: ''
    }
  },
  created: function () {
    this.init()
  },
  methods: {
    // 跳转到技术文档地址
    goDocUrl (url) {
      window.open(url)
    },
    // 跳转公告列表
    moreNotice () {
      this.$router.push({ name: 'notice_system' })
    },
    // 查看公告详细内容
    readContent (id) {
      this.$router.push({ path: 'generalBackstage/notice_content/' + id })
    },
    // 公告列表
    noticeList (type) {
      this.noticeKey = type
      const pageNum = 1
      const pageSize = 5
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': '{"type":"' + type + '"}'
      }
      var ListArr = []
      getNoticeList(data).then(res => {
        var resData = res.data.data
        resData.forEach((val, i) => {
          ListArr.push(val)
        })
        this.noticeArr = ListArr
      })
    },
    // 获取标签类型
    getNoticeType () {
      const pageNum = 1
      const pageSize = 999
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': '{"type":"NOTICE_TYPE"}'
      }
      var noticeArr = []
      getDictTable(data).then(res => {
        var resData = res.data.data
        resData.forEach((val, i) => {
          var value = val.value
          var name = val.name
          var obj = {
            value: value,
            name: name
          }
          noticeArr.push(obj)
        })
        this.noticeType = noticeArr
        this.noticeList(noticeArr[0].value)
      })
    },
    async init () {
      var rArr = []
      var routerArr = this.$router.options.routes
      var hasAccess = this.$store.state.user.access
      var SmsData, hasSmsData, CmsData, hasCmsData
      SmsData = await getSmsConfig()
      hasSmsData = SmsData.data.hasOwnProperty('data')
      this.SmsDataHas = hasSmsData
      CmsData = await getCmsConfig()
      hasCmsData = CmsData.data.hasOwnProperty('data')
      this.CmsDataHas = hasCmsData
      // console.log(hasAccess)
      // 将路由拉直
      routerArr.forEach(function (val, i) {
        var vPid = val.meta.pid
        if (vPid) {
          rArr.push(val)
        } else {}
        var children = val.hasOwnProperty('children')
        if (children) {
          var chiArr = val.children
          chiArr.forEach(function (value, index) {
            var value_name = value.name
            var value_Pid = value.meta.pid
            var hideInMenu = value.meta.hasOwnProperty('hideInMenu')
            var access = value.meta.hasOwnProperty('access')
            if (access) {
              var accessArr = value.meta.access
              var CanPush = false
              // 首先循环路由需要的权限
              accessArr.forEach((val, i) => {
                var RoutherAccess = val
                // 循环用户权限
                hasAccess.forEach((value, i) => {
                  var UserAccess = value
                  if (RoutherAccess === UserAccess) {
                    CanPush = true
                  } else {}
                })
              })
              if (CanPush) {
                if (!hideInMenu && value_Pid) {
                  // 判断是否需要判断第一次使用
                  if (value_Pid === 'sms') {
                    if (hasSmsData) {
                      rArr.push(value)
                    } else {
                      if (value_name === 'sms_config') {
                        rArr.push(value)
                      } else {}
                    }
                  } else if (value_Pid === 'cms') {
                    if (hasCmsData) {
                      rArr.push(value)
                    } else {
                      if (value_name === 'cms_config') {
                        rArr.push(value)
                      } else {}
                    }
                  } else if (value_Pid === 'car') {
                    if (hasCmsData) {
                      rArr.push(value)
                    } else {
                      if (value_name === 'car_system') {
                        rArr.push(value)
                      } else {}
                    }
                  } else if (value_Pid === 'other') {
                    if (hasCmsData) {
                      rArr.push(value)
                    } else {
                      if (value_name === 'cms_config' || value_name === 'car_system') {
                        rArr.push(value)
                      } else {}
                    }
                  } else { rArr.push(value) }
                } else {}
              } else {}
            } else {
              if (!hideInMenu && value_Pid) {
                // 判断是否需要判断第一次使用
                if (value_Pid === 'sms') {
                  if (hasSmsData) {
                    rArr.push(value)
                  } else {
                    if (value_name === 'sms_config') {
                      rArr.push(value)
                    } else {}
                  }
                } else if (value_Pid === 'cms') {
                  if (hasCmsData) {
                    rArr.push(value)
                  } else {
                    if (value_name === 'cms_config') {
                      rArr.push(value)
                    } else {}
                  }
                } else if (value_Pid === 'car') {
                  if (hasCmsData) {
                    rArr.push(value)
                  } else {
                    if (value_name === 'car_system') {
                      rArr.push(value)
                    } else {}
                  }
                } else if (value_Pid === 'other') {
                  if (hasCmsData) {
                    rArr.push(value)
                  } else {
                    if (value_name === 'cms_config' || value_name === 'car_system') {
                      rArr.push(value)
                    } else {}
                  }
                } else { rArr.push(value) }
              } else {}
            }
          })
        } else {}
      })
      this.chiMenuArr = rArr
      // 清除没有权限的菜单
      var pidArr = []
      var mainMenuArr = []
      this.$constant.MenuArr.forEach(function (val, i) {
        mainMenuArr.push(val)
      })
      rArr.forEach((value, i) => {
        var pid = value.meta.pid
        if (i === 0) {
          pidArr.push(pid)
        } else {
          var pidArrIndex = pidArr.findIndex(v => v === pid)
          if (pidArrIndex < 0) {
            pidArr.push(pid)
          } else {}
        }
      })
      mainMenuArr.forEach((value, i) => {
        var mainMenuId = value.id
        var hasMenuIdIndex = pidArr.findIndex(v => v === mainMenuId)
        if (hasMenuIdIndex < 0) {
          mainMenuArr.splice(i, 1)
        } else {}
      })
      this.mainMenuArr = mainMenuArr
      this.mainDocMenuArr = this.$constant.DocMenuArr
      this.getNoticeType()
    },
    goUrl (id) {
      var hasSmsData = this.SmsDataHas
      var hasCmsData = this.CmsDataHas
      var check = true
      if (id === 'sms' && !hasSmsData) {
        this.$refs.clearModal.clear_modal = true
        this.typeN = 'sms'
        this.typeV = '是否需要配置短信服务，进行下一步操作！'
        check = false
      } else if (id === 'cms' && !hasCmsData) {
        this.$refs.clearModal.clear_modal = true
        this.typeN = 'cms'
        this.typeV = '是否需要配置cms服务，进行下一步操作！'
        check = false
      } else {}
      if (!check) return
      var chiMenuArr = this.chiMenuArr
      var goArr = []
      chiMenuArr.forEach(function (value, index) {
        var value_Pid = value.meta.pid
        if (id === value_Pid) {
          goArr.push(value)
        } else {}
      })
      var tagNavList = this.$store.state.app.tagNavList
      // var newTagNavList = tagNavList.map(item => item)
      for (var i = tagNavList.length - 1; i > -1; i--) {
        var tagPid = tagNavList[i].meta.pid
        if (tagPid && tagPid !== id) {
          tagNavList.splice(i, 1)
          // var tagName = tagNavList[i].name
          // var index = tagNavList.findIndex(val => {
          //   return val.name === tagName
          // })
        } else {}
      }
      // localSave('tagNaveList', '')
      if (goArr.length < 1) {
        this.$Message.error('没有权限进入!')
      } else {
        var name = goArr[0].name
        this.$router.push({ name: name })
      }
    }
    // changeVue () {
    //   var tagNav = this.$parent.$parent.$children[0]
    //   tagNav.showTag = false
    //   var logoHeader = this.$root.$children[0].$children[0].$children[0].$children[1].$children[0].$children[0].$children[1]
    //   logoHeader.showLogo = true
    // }
  },
  mounted () {
    // this.changeVue()
  }
}
</script>

<style lang="less" scoped>
  .qrCode_img{
    width: 80%;
    height: auto;
  }
  .qrCode_title{}
  .qrCode_ul{}
  .menu_wrap{
    margin: 0 auto;
    /*width: 1000px;*/
    text-align: center;
  }
  .menu_header{
    text-align: left;
  }
  .menu_row{
    margin: 0 auto;
    /*width: 800px;*/
  }
  .menu_obj{
    color: #4e4e4e;
    cursor: pointer;
    height: 90px;
  }
  .menu_obj:hover {
    background: #f5f5f6;
    box-shadow: 0 0 8px 0 rgba(0, 0, 0, .15);
    color: #6c6c6c;
  }
  .menu_div{
    margin-top: 2%;
  }
  .menu_icon i{
    font-size: 45px;
  }
  .menu_title {
    font-weight: 600;
    font-size: 16px;
    margin-top: 8px;
  }
  .tab_wrap{
    position: relative;
    height: 300px;
  }
  .tabs_wrap{
    margin-bottom: 20px;
  }
  .notice_obj{
    overflow: hidden;/*超出部分隐藏*/
    white-space: nowrap;/*不换行*/
    text-overflow:ellipsis;/*超出部分文字以...显示*/
    cursor: pointer;
    color: #6a6e71;
  }
  .notice_operate{
    position: absolute;
    left: 30px;
    bottom: 15px;
  }
  .notice_operate span{
    cursor: pointer;
    color: #5a97d8;
  }
  .notice_title:hover{
    color: #5a97d8;
  }
  .wrap_bottom{
    margin-top: 16px;
  }
</style>
