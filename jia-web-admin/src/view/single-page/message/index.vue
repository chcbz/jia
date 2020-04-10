<template>
  <Card shadow class="ivu-card-new">
    <div class="message-div">
      <div class="message-page-con message-category-con">
        <Menu width="auto" active-name="unread" @on-select="handleSelect">
          <MenuItem name="unread">
            <span class="category-title">未读消息</span><Badge style="margin-left: 10px" :count="listDic['unread_count']"></Badge>
          </MenuItem>
          <MenuItem name="readed">
            <span class="category-title">已读消息</span><Badge style="margin-left: 10px" class-name="gray-dadge" :count="listDic['readed_count']"></Badge>
          </MenuItem>
          <MenuItem name="trash">
            <span class="category-title">回收站</span><Badge style="margin-left: 10px" class-name="gray-dadge" :count="listDic['trash_count']"></Badge>
          </MenuItem>
        </Menu>
      </div>
      <div class="message-page-con message-list-con" ref="msgList" v-scroll>
        <Spin fix v-if="listLoading" size="large"></Spin>
        <Menu
          width="auto"
          active-name=""
          :class="titleClass"
          @on-select="handleView"
        >
          <MenuItem v-for="(item,index) in messageList" :name="'msg_'+item.id" :key="index">
            <div>
              <p class="msg-title">{{ item.content }}</p>
              <Badge status="default" :text="$constant.formatDateTime(item.createTime)" />
              <Button
                style="margin-left: 20px;"
                size="small"
                :icon="currentMessageType === 'readed' ? 'md-trash' : 'md-redo'"
                :title="currentMessageType === 'readed' ? '删除' : '还原'"
                type="text"
                v-show="currentMessageType !== 'unread'"
                @click.native.stop="removeMsg(item)"></Button>
              <Button
                size="small"
                icon="md-trash"
                title="永久删除"
                type="text"
                v-show="currentMessageType === 'trash'"
                @click.native.stop="deleteMsg(item)"></Button>
            </div>
          </MenuItem>
        </Menu>
      </div>
      <div class="message-other-wrap">
        <iframe
          :src="resUrl"
          class="message-iframe"
          scrolling="auto"
          frameborder="0"
        ></iframe>
      </div>
    </div>
  </Card>
</template>

<script>
import { getMsgList, hasRead, removeReaded, restoreTrash, deleteMsg } from '@/api/user'
import { mapActions } from 'vuex'
export default {
  name: 'message_page',
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
          if (mild > -1) {
            vm.addMsg()
          }
        }
      }
    }
  },
  data () {
    return {
      listLoading: true,
      contentLoading: false,
      currentMessageType: 'unread',
      messageContent: '',
      resUrl: '',
      pageSize: 10, // 请求大小
      listDic: {
        unread: [],
        unread_count: 0,
        unread_last_i: 0, // 未读列表下拉的次数
        unread_max_pageNum: 0, // 未读列表最大可请求页数
        readed: [],
        readed_count: 0,
        readed_last_i: 0, // 未读列表下拉的次数
        readed_max_pageNum: 0, // 已读列表最大可请求页数
        trash: [],
        trash_count: 0,
        trash_last_i: 0, // 未读列表下拉的次数
        trash_max_pageNum: 0 // 回收站列表最大可请求页数
      }
    }
  },
  computed: {
    messageList () {
      return this.listDic[this.currentMessageType]
    },
    titleClass () {
      return {
        'not-unread-list': this.currentMessageType !== 'unread'
      }
    }
  },
  methods: {
    ...mapActions([
      'getMessageList'
    ]),
    // 根据类型进行换页
    addMsg () {
      var type = this.currentMessageType
      if (type === 'unread') { // 未读
        this.listDic['unread_last_i'] = this.listDic['unread_last_i'] + 1
        if (this.listDic['unread_last_i'] > this.listDic['unread_max_pageNum']) {} else {
          this.getMsg()
        }
      } else if (type === 'readed') { // 已读
        this.listDic['readed_last_i'] = this.listDic['readed_last_i'] + 1
        if (this.listDic['readed_last_i'] > this.listDic['readed_max_pageNum']) {} else {
          this.getMsg()
        }
      } else { // 回收站
        this.listDic['trash_last_i'] = this.listDic['trash_last_i'] + 1
        if (this.listDic['trash_last_i'] > this.listDic['trash_max_pageNum']) {} else {
          this.getMsg()
        }
      }
    },
    // 获取信息列表
    getMsg () {
      var type = this.currentMessageType
      var status = 0
      var pageNum = 0
      const pageSize = this.pageSize
      const userId = this.$store.state.user.userId
      if (type === 'unread') { // 未读
        pageNum = this.listDic['unread_last_i']
        status = 1
      } else if (type === 'readed') { // 已读
        pageNum = this.listDic['readed_last_i']
        status = 2
      } else { // 回收站
        pageNum = this.listDic['trash_last_i']
        status = 0
      }
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': '{"status":"' + status + '","userId":"' + userId + '"}'
      }
      getMsgList(data).then(res => {
        var Rdata = res.data.data
        var _this = this
        Rdata.forEach((val, i) => {
          _this.listDic[_this.currentMessageType].push(val)
        })
      })
    },
    // 停止动画效果
    stopLoading (name) {
      this[name] = false
    },
    // 移除数据，并还原数组
    initArr (id, from, to) {
      var index = -1
      var unreadArr = this.listDic[from]
      unreadArr.forEach((val, i) => {
        var ValId = (val.id).toString()
        var MsgId = id.toString()
        if (ValId === MsgId) {
          index = i
        } else {}
      })
      this.listDic[from].splice(index, 1)// 移除对应的数据
      this.listDic[from + '_count'] = this.listDic[from + '_count'] - 1
      if (to) {
        // 还原目标数组
        var status = 0
        var pageNum = 1
        const pageSize = this.pageSize
        const userId = this.$store.state.user.userId
        if (to === 'unread') { // 未读
          status = 1
        } else if (to === 'readed') { // 已读
          status = 2
        } else { // 回收站
          status = 0
        }
        var data = {
          'pageNum': pageNum,
          'pageSize': pageSize,
          'search': '{"status":"' + status + '","userId":"' + userId + '"}'
        }
        getMsgList(data).then(res => {
          var resArr = res.data
          var Rdata = resArr.data
          var total = resArr.total
          var _this = this
          this.listDic[to] = []
          this.listDic[to + '_count'] = resArr.total
          this.listDic[to + '_last_i'] = 1
          this.listDic[to + '_max_pageNum'] = parseInt(total / pageSize) + 1// 最大可请求页数
          Rdata.forEach((val, i) => {
            _this.listDic[to].push(val)
          })
        })
      } else {}
    },
    // 把一个未读消息标记为已读
    hasRead (id) {
      hasRead(id).then((res) => {
        if (res.data.msg === 'ok') {
          this.initArr(id, 'unread', 'readed')
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 删除一个已读消息到回收站
    removeReaded (id) {
      removeReaded(id).then((res) => {
        if (res.data.msg === 'ok') {
          this.initArr(id, 'readed', 'trash')
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 还原一个已删除消息到已读消息
    restoreTrash (id) {
      restoreTrash(id).then((res) => {
        if (res.data.msg === 'ok') {
          this.initArr(id, 'trash', 'readed')
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 永久删除信息
    deleteTrash (id) {
      deleteMsg(id).then((res) => {
        if (res.data.msg === 'ok') {
          this.initArr(id, 'trash')
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Message.error('请联系管理员')
      })
    },
    // 切换类型
    handleSelect (name) {
      this.$refs.msgList.scrollTop = 0
      this.currentMessageType = name
      // 当长度为0时取消loading效果
      var length = this.messageList.length
      if (length === 0) { this.stopLoading('listLoading') } else {}
    },
    // 点击进行跳转
    handleView (id) {
      var msgId = parseInt(id.split('_')[1])
      this.contentLoading = true
      const item = this.messageList.find(item => item.id === msgId)
      var url = item.url
      this.resUrl = url
      if (this.currentMessageType === 'unread') {
        this.hasRead(msgId)
        this.addMsg()
      } else {}
      this.stopLoading('contentLoading')
    },
    // 消息移到回收站或恢复回收站信息
    removeMsg (item) {
      item.loading = true
      const id = item.id
      if (this.currentMessageType === 'readed') {
        this.removeReaded(id)
        this.addMsg()
        this.stopLoading('listLoading')
      } else {
        this.restoreTrash(id)
        this.addMsg()
        this.stopLoading('listLoading')
      }
    },
    // 消息永久删除
    deleteMsg (item) {
      item.loading = true
      const id = item.id
      this.deleteTrash(id)
      this.addMsg()
      this.stopLoading('listLoading')
    },
    // 初始化vue
    initVue () {
      this.listLoading = true
      // 请求获取消息列表
      this.getMessageList().then((res) => {
        // 初始化数据,获取3类消息的最大请求页数
        const pageSize = this.pageSize
        this.listDic['unread'] = res['0'].unread.data
        var UnreadTotal = res['0'].unread.total
        this.listDic['unread_count'] = UnreadTotal
        this.listDic['unread_last_i'] = 1
        this.listDic['unread_max_pageNum'] = parseInt(UnreadTotal / pageSize) + 1// 最大可请求页数
        this.listDic['readed'] = res['0'].readed.data
        var ReadedTotal = res['0'].readed.total
        this.listDic['readed_count'] = ReadedTotal
        this.listDic['readed_last_i'] = 1
        this.listDic['readed_max_pageNum'] = parseInt(ReadedTotal / pageSize) + 1// 最大可请求页数
        this.listDic['trash'] = res['0'].trash.data
        var TrashTotal = res['0'].trash.total
        this.listDic['trash_count'] = TrashTotal
        this.listDic['trash_last_i'] = 1
        this.listDic['trash_max_pageNum'] = parseInt(TrashTotal / pageSize) + 1// 最大可请求页数
        this.stopLoading('listLoading')
      }).catch(() => {
        this.stopLoading('listLoading')
      })
    }
  },
  mounted () {
    this.initVue()
  }
}
</script>

<style lang="less" scoped>
  .message-iframe{
    width: 100%;
    height: 100%;
  }
  .message-div{
    display: -webkit-box;
    height: 100%;
    overflow: hidden;
  }
  .message-other-wrap{
    width: 960px;
    margin-left: 5px;
    background: #f0f0f0;
    /*width: 380px;*/
    height: 100%;
  }
  .ivu-card-new{
    height: 90%;
    width: 1600px;
    margin: 0 auto;
  }
  /deep/.ivu-card-body{
    height: 100%;
  }
  /deep/.message-page{
    &-con{
      /*height: ~"calc(100vh - 176px)";*/
      display: inline-block;
      vertical-align: top;
      position: relative;
      &.message-category-con{
        border-right: 1px solid #e6e6e6;
        height: 100%;
        width: 200px;
      }
      &.message-list-con{
        border-right: 1px solid #e6e6e6;
        overflow: auto;
        height: 100%;
        width: 400px;
      }
      &.message-view-con{
        position: absolute;
        left: 446px;
        top: 16px;
        right: 16px;
        bottom: 16px;
        overflow: auto;
        padding: 12px 20px 0;
        .message-view-header{
          margin-bottom: 20px;
          .message-view-title{
            display: inline-block;
          }
          .message-view-time{
            margin-left: 20px;
          }
        }
      }
      .category-title{
        display: inline-block;
        width: 65px;
      }
      .gray-dadge{
        background: gainsboro;
      }
      .not-unread-list{
        .msg-title{
          color: rgb(170, 169, 169);
        }
        .ivu-menu-item{
          .ivu-btn.ivu-btn-text.ivu-btn-small.ivu-btn-icon-only{
            display: none;
          }
          &:hover{
            .ivu-btn.ivu-btn-text.ivu-btn-small.ivu-btn-icon-only{
              display: inline-block;
            }
          }
        }
      }
    }
  }
</style>
