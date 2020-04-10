import axios from '@/libs/api.request'
import constant from '@/libs/constant'
import { localRead } from '@/libs/util'
import Qs from 'qs'

// 用户登录
export const login = ({ userName, password }) => {
  const data = {
    'grant_type': 'password',
    'username': userName,
    'password': password
  }
  return axios.request({
    url: constant.ajaxurl + '/oauth/token',
    data: data,
    // changeOrigin:true,//允许跨域
    headers: { 'Authorization': 'Basic amlhX2NsaWVudDpqaWFfc2VjcmV0' },
    transformRequest: [function (data) { // 在请求之前对data传参进行格式转换
      data = Qs.stringify(data)
      return data
    }],
    method: 'post'
  })
}

// 刷新token
export const RefreshToken = () => {
  const data = {
    'grant_type': 'refresh_token',
    'refresh_token': localRead('tokenarr').refresh_token
  }
  return axios.request({
    url: constant.ajaxurl + '/oauth/token',
    data: data,
    headers: { 'Authorization': 'Basic amlhX2NsaWVudDpqaWFfc2VjcmV0' },
    transformRequest: [function (data) { // 在请求之前对data传参进行格式转换
      data = Qs.stringify(data)
      return data
    }],
    method: 'post'
  })
}

// 注册客户端
export const registerClient = (x, worktoken) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/oauth/client/register',
    data: data,
    headers: { 'Authorization': 'Bearer' + worktoken },
    method: 'post'
  })
}

// 获取用户信息
export const getUserInfo = () => {
  var key = localRead('username')
  var type = ''
  var key_type = key.substring(0, 3)
  if (key_type === 'mb-') {
    key = key.split('-')[1]
    type = 'phone'
  } else {
    type = 'username'
  }
  return axios.request({
    url: constant.ajaxurl + '/user/get',
    params: {
      // 'access_token': getToken(),
      'type': type,
      'key': key
    },
    method: 'get'
  })
}

// 获取用户权限
export const getUserPerms = (x) => {
  const data = x
  let result = axios.request({
    url: constant.ajaxurl + '/user/get/perms',
    data: data,
    method: 'post'
  })
  return result
}

// 用户消息列表
export const getMsgList = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/msg/list',
    data: data,
    method: 'post'
  })
}

// 用户信息已读
export const hasRead = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/msg/read',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 移动用户信息到回收站
export const removeReaded = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/msg/recycle',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 还原用户信息
export const restoreTrash = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/msg/restore',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 永久删除用户消息
export const deleteMsg = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/msg/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}
