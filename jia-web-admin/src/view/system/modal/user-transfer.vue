<template>
  <Modal
    :title="UserTransfer_title"
    v-model="UserTransfer_modal"
    :closable="false"
    :mask-closable="false">
    <Row type="flex"  align="middle" class="code-row-bg">
      <!--选择容器-->
      <Col span="11">
        <div class="linkage_div">
          <Card>
            <p slot="title">
             所有用户
            </p>
            <div class="linkage_content">
              <Input search  placeholder="搜索" v-model="searchUserInitData"/>
              <ul class="have_content_ul" v-scroll>
                <li v-for="(option, v) in NewUserInitData"  :key="v">
                  <Tag type="dot" :color="checkClick(option.key)">{{option.label}}</Tag>
                  <Icon type="md-add" class="icon_add" v-show="(checkClick(option.key)=='primary')?false:true" @click="choiceUser(option.key)"/>
                </li>
              </ul>
            </div>
          </Card>
        </div>
      </Col>
      <!--装饰箭头-->
      <Col span="2">
        <div class="linkage_icon">
          <Icon type="md-repeat" />
        </div>
      </Col>
      <Col span="11">
        <div class="linkage_div">
          <Card>
            <p slot="title">
             已选用户
            </p>
            <div class="linkage_content">
              <Input search  placeholder="搜索" v-model="searchUserHaveData"/>
              <ul class="have_content_ul">
                <li v-for="(option, v) in NewUserHaveData"  :key="v">
                  <Tag type="dot" color="success" closable @on-close="removeUser(option.key)">{{option.label}}</Tag>
                </li>
              </ul>
            </div>
          </Card>
        </div>
      </Col>
    </Row>
    <div slot="footer">
      <Button type="text" size="large" @click="UserTransfer_modal=false">关闭</Button>
    </div>
  </Modal>
</template>

<script>
import { getOrgUsers, searchUser } from '@/api/data'
export default {
  // 自定义事件
  directives: {
    scroll: {
      inserted: function (el, binding, vnode) {
        // 获取vue实例对象
        let vm = vnode.context
        // 无限滚动
        el.onscroll = (event) => {
          const offsetHeight = el.offsetHeight
          const scrollTop = el.scrollTop
          const scrollHeight = el.scrollHeight
          const mild = (offsetHeight + scrollTop) - scrollHeight
          if (mild > 1) { // 此处大于1是因为border总和为2px
            vm.last_i = vm.last_i + 1// 记录下拉的次数
            vm.adduser()
          }
        }
      }
    }
  },
  data () {
    return {
      searchUserInitData: '',
      UserTransfer_title: '',
      UserTransfer_modal: false,
      NewUserInitData: [],
      UserInitData: [],
      last_i: 0, // 下拉的次数
      max_pageNum: 0, // 最大可请求页数
      pageSize: 10, // 请求大小
      searchUserHaveData: '',
      UserHaveData: [],
      UserTransfer_templateId: ''
    }
  },
  methods: {
    // 已选中增加背景色
    checkClick (key) {
      var Have = 'default'
      this.UserHaveData.forEach((element, i) => {
        if (element.key === key) {
          Have = 'primary'
        } else {}
        return Have
      })
      return Have
    },
    // 选择用户
    choiceUser (key) {
      var obj = this.UserInitData.find((element) => (element.key === key))
      var h_key = obj.key
      var judge = this.UserHaveData.find((element) => (element.key === h_key))
      if (judge) {} else {
        this.$emit('AddObj', [obj])
        // this.UserHaveData.push(obj)
      }
    },
    // 去掉已选择的用户
    removeUser (key) {
      this.$emit('RemoveObj', key)
    },
    // 获取用户列表
    getUser (pageNum) {
      const pageSize = this.pageSize
      var orgId = this.$store.state.user.position
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': '{"orgId":"' + orgId + '"}'
      }
      getOrgUsers(data).then(res => {
        var _this = this
        var result = res.data.data
        var total = res.data.total
        this.max_pageNum = parseInt(total / pageSize) + 1// 最大可请求页数
        result.forEach((val, i) => {
          var id = val.id
          var label = val.nickname
          var obj = { 'key': id, 'label': label }
          _this.UserInitData.push(obj)
        })
      })
    },
    // 下拉到底部触发
    adduser () {
      var last_i = this.last_i
      var page = last_i + 1
      var max_pageNum = this.max_pageNum
      if (last_i > max_pageNum) {} else {
        this.getUser(page)
      }
    }
  },
  watch: {
    // 实时查询
    searchUserInitData: {
      handler (newName, oldName) {
        var search = this.searchUserInitData
        var UserInitData = this.UserInitData
        if (!search) {
          this.NewUserInitData = UserInitData
        } else {
          var _this = this
          this.NewUserInitData = []
          var pageSize = this.pageSize
          var data = {
            'pageNum': 1,
            'pageSize': pageSize,
            'search': '{"nickname":"' + search + '"}'
          }
          searchUser(data).then(res => {
            if (res.data.msg === 'ok') {
              var result = res.data.data
              result.forEach((val, i) => {
                var id = val.id
                var label = val.nickname
                var obj = { 'key': id, 'label': label }
                _this.NewUserInitData.push(obj)
              })
            } else { this.$Message.error(res.data.msg) }
          })
        }
      }
      // deep: true,
      // immediate: true
    }
  },
  computed: {
    // 模糊查询
    NewUserHaveData () {
      var _this = this
      var NewUserHaveData = []
      this.UserHaveData.map(function (item) {
        if (item.label.search(_this.searchUserHaveData) !== -1) {
          NewUserHaveData.push(item)
        }
      })
      return NewUserHaveData
    }
  },
  created () {
    // this.getUser(1)
  }
}
</script>

<style lang="less" scoped>
  .linkage_icon{
    text-align: center;
  }
  .linkage_icon i{
    font-size: 28px;
  }
  .li_choice{
    background: #d0d0d0;
  }
  /deep/ .linkage_div .ivu-card-body{
    padding: 6px;
  }
  /deep/ .linkage_div .ivu-card-bordered{
    border: 0.08rem solid #dcdee2;
    border-radius: 6px 6px 0 0;
  }
  /deep/ .linkage_div .ivu-card-head{
    background: #f9fafc;
    border-radius: 6px 6px 0 0;
    text-align: center;
  }
  /deep/ .ivu-input-search-icon{
    cursor: auto;
  }
  .linkage_div p{
    color: #77797b;
  }
  .choice{
    background: #e4e4e4;
  }
  .message_div{
    margin: 10px 0;
  }
  .have_content_ul{
    border: 1px solid #dcdee2;
    margin: 5px  0;
    height: 325px;
    overflow: auto;
  }
  .have_content_ul li{
    /*padding: 0 15px;*/
    position: relative;
    margin: 1px auto;
  }
  .icon_add{
    cursor: pointer;
    position: absolute;
    top: 9px;
    right: 10px;
    color: #c3c3c3;
  }
  .icon_add:hover{
    color: #a3a6a9;
    opacity: 1;
    font-size: 14px;
  }
  /deep/.ivu-tag-dot{
    width: 100%;
  }
  /deep/.ivu-tag{
    cursor: context-menu;
    margin: 0;
  }
  /deep/.ivu-icon-ios-close{
    line-height: 35px;
    float: right;
  }
</style>
