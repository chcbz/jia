import Vue from 'vue'
import Router from 'vue-router'
import routes from './routers'
import store from '@/store'
import { RefreshToken } from '@/api/user'
import iView from 'iview'
import { setToken, localRead, getToken, canTurnTo, setTitle } from '@/libs/util'
import config from '@/config'
const { homeName } = config

Vue.use(Router)
const router = new Router({
  routes,
  mode: 'history'
  // mode: 'hash'
})
const LOGIN_PAGE_NAME = 'login'
const REGISTER_PAGE_NAME = 'register'

const turnTo = (to, access, next) => {
  if (canTurnTo(to.name, access, routes)) next() // 有权限，可访问
  else next({ replace: true, name: 'error_401' }) // 无权限，重定向到401页面
}

router.beforeEach((to, from, next) => {
  // console.log(to, from)
  iView.LoadingBar.start()
  const token = getToken()
  const tokenarr = localRead('tokenarr')
  var expires_in = tokenarr.expires_in
  // token已经过期
  var now_time = new Date().getTime()
  if (!token) {
    if (to.name !== LOGIN_PAGE_NAME && to.name !== REGISTER_PAGE_NAME) {
      // 未登录且要跳转的页面不是登录页
      next({
        name: LOGIN_PAGE_NAME // 跳转到登录页
      })
    } else {
      // 未登录且要跳转的页面是注册页或登录页
      next()
    }
  } else {
    // 注册页面无限制
    if (to.name === REGISTER_PAGE_NAME) {
      next()
    } else {
      if (expires_in < now_time) {
        if (to.name === LOGIN_PAGE_NAME) {
          next()
        } else {
          next({
            name: LOGIN_PAGE_NAME // 跳转到登录页
          })
        }
      } else if (now_time < expires_in && expires_in < now_time + 10 * 60 * 1000) { // token失效前十分钟，自动刷新
        RefreshToken().then(res => {
          const data = res.data
          setToken(data)
          if (to.name === LOGIN_PAGE_NAME) {
            next({
              name: homeName // 跳转到登录页
            })
          } else {
            trueCheck(to, next)
          }
        }).catch(err => {
          console.log(err)
          setToken('')
          if (to.name === LOGIN_PAGE_NAME) {
            next()
          } else {
            next({
              name: LOGIN_PAGE_NAME
            })
          }
        })
        // return true
      } else { // token正常
        if (to.name === LOGIN_PAGE_NAME) {
          next({
            name: homeName // 跳转到首页
          })
        } else {
          trueCheck(to, next)
        }
      }
    }
  }
})
const trueCheck = (to, next) => {
  if (store.state.user.hasGetInfo) {
    turnTo(to, store.state.user.access, next)
  } else {
    // setTimeout(() => {})
    store.dispatch('getUserAll').then(user => {
      // 拉取用户信息，通过用户权限和跳转的页面的name来判断是否有权限访问;access必须是一个数组，如：['super_admin'] ['super_admin', 'admin']
      // turnTo(to, [], next)
      if (to.name === LOGIN_PAGE_NAME) {
        next({
          name: homeName
        })
      } else {
        turnTo(to, store.state.user.access, next)
      }
    }).catch(() => {
      setToken('')
      if (to.name === LOGIN_PAGE_NAME) {
        next()
      } else {
        next({
          name: LOGIN_PAGE_NAME
        })
      }
    })
  }
}
router.afterEach(to => {
  setTitle(to, router.app)
  iView.LoadingBar.finish()
  window.scrollTo(0, 0)
})

export default router
