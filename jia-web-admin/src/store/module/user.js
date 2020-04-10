import {
  login,
  getUserInfo,
  hasRead,
  removeReaded,
  restoreTrash,
  getMsgList
} from '@/api/user'
import { getOrgInfo } from '@/api/data'
import { setToken, getToken, localSave } from '@/libs/util'
import constant from '@/libs/constant'

export default {
  state: {
    orgDate: {},
    userDate: {},
    userName: '',
    userId: '',
    avatar: '',
    position: '',
    avatorImgPath: '',
    token: getToken(),
    access: '',
    hasGetInfo: false,
    unreadCount: 0,
    messageUnreadList: [],
    messageUnreadListLength: 0,
    messageReadedList: [],
    messageReadedListLength: 0,
    messageTrashList: [],
    messageTrashListLength: 0,
    messageContentStore: {}
  },
  mutations: {
    // setAvator (state, avatorPath) {
    //   state.avatorImgPath = avatorPath
    // },
    setOrgDate (state, data) {
      var xhr = new XMLHttpRequest()
      xhr.open('GET', constant.ajaxurl + '/org/get?id=' + data + '&access_token=' + getToken(), false)
      xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8')
      xhr.send(null)
      var result = JSON.parse(xhr.responseText).data
      state.orgDate = result
    },
    setUserDate (state, data) {
      state.userDate = data
    },
    setUserId (state, id) {
      state.userId = id
    },
    setUserAvatar (state, avatar) {
      state.avatar = avatar
    },
    setPosition (state, id) {
      state.position = id
    },
    setUserName (state, name) {
      state.userName = name
    },
    setAccess (state, id) {
      if (id === '') {
        state.access = []
      } else {
        var xhr = new XMLHttpRequest()
        xhr.open('POST', constant.ajaxurl + '/user/get/perms', false)
        xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8')
        xhr.setRequestHeader('Authorization', 'Bearer ' + getToken())
        var permsData = {
          'pageNum': 1,
          'search': '{"id":' + id + '}'
        }
        xhr.send(JSON.stringify(permsData))
        var result = JSON.parse(xhr.responseText).data
        var permsArr = []
        result.forEach((val, i) => {
          var module = val.module
          var func = val.func
          var accessName = module + '_' + func
          permsArr.push(accessName)
        })
        state.access = permsArr
      }
    },
    setToken (state, token) {
      state.token = token
      setToken(token)
    },
    setHasGetInfo (state, status) {
      state.hasGetInfo = status
    },
    setMessageCount (state, count) {
      state.unreadCount = count
    },
    setMessageUnreadList (state, list) {
      state.messageUnreadList = list.data
      state.messageUnreadListLength = list.total
    },
    setMessageReadedList (state, list) {
      state.messageReadedList = list.data
      state.messageReadedListLength = list.total
    },
    setMessageTrashList (state, list) {
      state.messageTrashList = list.data
      state.messageTrashListLength = list.total
    },
    updateMessageContentStore (state, { id, content }) {
      state.messageContentStore[id] = content
    },
    moveMsg (state, { from, to, msgId }) {
      var index = -1
      state[from].forEach((val, i) => {
        var id = (val.id).toString()
        var MsgId = msgId.toString()
        if (id === MsgId) {
          index = i
          state[to].unshift(val)
        } else {}
      })
      const msgItem = state[from].splice(index, 1)[0]
      msgItem.loading = false
    }
  },
  getters: {
    messageUnreadCount: state => state.messageUnreadListLength,
    messageReadedCount: state => state.messageReadedListLength,
    messageTrashCount: state => state.messageTrashListLength
  },
  actions: {
    // 登录
    handleLogin ({ commit }, { userName, password, type, _this }) {
      userName = userName.trim()
      return new Promise((resolve, reject) => {
        login({
          userName,
          password
        }).then(res => {
          localSave('username', userName)
          const data = res.data
          commit('setToken', data)
          resolve()
        }).catch(err => {
          if (type === 'user') {
            _this.$Message.error('登录失败！')
          } else if (type === 'mobile') {
            _this.$Message.error('登录失败！')
          } else {}
          reject(err)
        })
      })
    },
    // 退出登录
    handleLogOut ({ state, commit }) {
      // 如果你的退出登录无需请求接口，则可以直接使用下面三行代码而无需使用logout调用接口
      commit('setToken', '')
      commit('setAccess', '')
    },
    // 获取用户相关信息
    getUserInfo ({ state, commit }) {
      return new Promise((resolve, reject) => {
        try {
          getUserInfo().then(res => {
            const data = res.data
            commit('setUserName', data.data.nickname)
            commit('setUserId', data.data.id)
            commit('setUserAvatar', data.data.avatar)
            commit('setPosition', data.data.position)
            commit('setHasGetInfo', true)
            commit('setUserDate', data.data)
            resolve(data)
          }).catch(err => {
            reject(err)
          })
        } catch (error) {
          reject(error)
        }
      })
    },
    getUserAll ({ state, commit }) {
      return new Promise((resolve, reject) => {
        try {
          getUserInfo().then(res => {
            const data = res.data
            commit('setUserName', data.data.nickname)
            commit('setUserId', data.data.id)
            commit('setUserAvatar', data.data.avatar)
            commit('setPosition', data.data.position)
            commit('setHasGetInfo', true)
            commit('setUserDate', data.data)
            commit('setAccess', data.data.id)
            commit('setOrgDate', data.data.position)
            resolve(data)
          }).catch(err => {
            reject(err)
          })
        } catch (error) {
          reject(error)
        }
      })
    },
    // 此方法用来获取未读消息条数，接口只返回数值，不返回消息列表
    getUnreadMessageCount ({ state, commit }) {
      var userId = state.userId
      var data = {
        'pageNum': 1,
        'pageSize': 5,
        'search': '{"status":"' + 1 + '","userId":"' + userId + '"}'
      }
      getMsgList(data).then(res => {
        const total = res.data.total
        commit('setMessageCount', total)
      })
    },
    // 获取消息列表，其中包含未读、已读、回收站三个列表
    getMessageList ({ state, commit }) {
      return new Promise((resolve, reject) => {
        var userId = state.userId
        // 获取未读信息
        var undata = {
          'pageNum': 1,
          'pageSize': 20,
          'search': '{"status":"' + 1 + '","userId":"' + userId + '"}'
        }
        getMsgList(undata).then(res => {
          var unread = res.data
          commit('setMessageUnreadList', unread)
          // 获取已读信息
          var eddata = {
            'pageNum': 1,
            'pageSize': 20,
            'search': '{"status":"' + 2 + '","userId":"' + userId + '"}'
          }
          getMsgList(eddata).then(res => {
            var readed = res.data
            commit('setMessageReadedList', readed)
            // 获取回收站信息
            var trashdata = {
              'pageNum': 1,
              'pageSize': 20,
              'search': '{"status":"' + 0 + '","userId":"' + userId + '"}'
            }
            getMsgList(trashdata).then(res => {
              var trash = res.data
              commit('setMessageTrashList', trash)
              resolve([{ unread, readed, trash }])
            })
            // end
          })
          // end
        })
        // end
      })
    },
    // 把一个未读消息标记为已读
    hasRead ({ state, commit }, { msgId }) {
      return new Promise((resolve, reject) => {
        hasRead(msgId).then(() => {
          commit('moveMsg', {
            from: 'messageUnreadList',
            to: 'messageReadedList',
            msgId
          })
          state.messageUnreadListLength = state.messageUnreadListLength - 1
          state.messageReadedListLength = state.messageReadedListLength + 1
          commit('setMessageCount', state.unreadCount - 1)
          resolve()
        }).catch(error => {
          reject(error)
        })
      })
    },
    // 删除一个已读消息到回收站
    removeReaded ({ state, commit }, { id }) {
      return new Promise((resolve, reject) => {
        var msgId = id
        removeReaded(msgId).then(() => {
          commit('moveMsg', {
            from: 'messageReadedList',
            to: 'messageTrashList',
            msgId
          })
          state.messageReadedListLength = state.messageReadedListLength - 1
          state.messageTrashListLength = state.messageTrashListLength + 1
          resolve()
        }).catch(error => {
          reject(error)
        })
      })
    },
    // 还原一个已删除消息到已读消息
    restoreTrash ({ state, commit }, { id }) {
      return new Promise((resolve, reject) => {
        var msgId = id
        restoreTrash(msgId).then(() => {
          commit('moveMsg', {
            from: 'messageTrashList',
            to: 'messageReadedList',
            msgId
          })
          state.messageReadedListLength = state.messageReadedListLength + 1
          state.messageTrashListLength = state.messageTrashListLength - 1
          resolve()
        }).catch(error => {
          reject(error)
        })
      })
    }
  }
}
