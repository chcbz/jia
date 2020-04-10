import axios from '@/libs/api.request'
// import axios from 'axios'
import constant from '@/libs/constant'
// import { getToken } from '@/libs/util'
// import Qs from 'qs'

// 获取工作流token
export const getWorkToken = () => {
  var grant_type = constant.grant_type
  var client_id = constant.client_id
  var client_secret = constant.client_secret
  return axios.request({
    url: constant.ajaxurl + '/oauth/token',
    params: {
      'grant_type': grant_type,
      'client_id': client_id,
      'client_secret': client_secret
    },
    method: 'get'
  })
}

// JS-SDK-获取签名信息
export const createJsapi = (data, worktoken) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/createJsapiSignature',
    params: data,
    headers: { 'Authorization': 'Bearer' + worktoken },
    method: 'get'
  })
}

// 获取客户端信息
export const getOauthClient = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/oauth/client/get',
    params: data,
    method: 'get'
  })
}

// 更新客户端信息
export const updateOauthClient = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/oauth/client/update',
    data: data,
    method: 'post'
  })
}

// 显示静态文件
export const FileList = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/file/list',
    data: data,
    method: 'post'
  })
}

// 删除静态文件
export const delFile = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/file/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 上传静态文件(已取消)
export const uploadFile = (type, file) => {
  var data = new FormData() // 创建form对象
  data.append('type', type)// 通过append向form对象添加数据
  data.append('file', file)
  return axios.request({
    url: constant.ajaxurl + '/file/upload',
    data: data,
    method: 'post'
  })
}

// 获取手机验证码
export const createsmsCode = (data, worktoken) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/gen',
    params: data,
    headers: { 'Authorization': 'Bearer' + worktoken },
    method: 'get'
  })
}

// 验证机验证码
export const smsValidateCode = (data, worktoken) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/validate',
    params: data,
    headers: { 'Authorization': 'Bearer' + worktoken },
    method: 'get'
  })
}

// 用户列表
export const getUserTable = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/user/list',
    data: data,
    method: 'post'
  })
}

// 搜索用户
export const searchUser = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/user/search',
    data: data,

    method: 'post'
  })
}

// 更新用户角色
export const changeRoleUser = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/user/role/change',
    data: data,
    method: 'post'
  })
}

// 更新用户所属组
export const changeGroupUser = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/user/group/change',
    data: data,
    method: 'post'
  })
}

// 创建用户
export const createUser = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/user/create',
    data: data,
    method: 'post'
  })
}

// 获取用户信息
export const getUser = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/user/get',
    params: data,
    method: 'get'
  })
}

// 修改用户头像
export const updateUserAvatar = (id, file) => {
  var data = new FormData() // 创建form对象
  data.append('id', id)// 通过append向form对象添加数据
  data.append('file', file)
  return axios.request({
    url: constant.ajaxurl + '/user/update/avatar',
    data: data,
    method: 'post'
  })
}

// 修改用户
export const updateUser = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/user/update',
    data: data,

    method: 'post'
  })
}

// 删除用户
export const delUser = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/user/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 获取用户组织
export const getUserOrg = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/user/get/orgs',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 修改当前用户职位
export const changeUserPosition = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/user/position/change',
    params: {
      'position': x
    },

    method: 'get'
  })
}

// 用户组列表
export const getGroupList = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/list',
    data: data,
    method: 'post'
  })
}

// 获取用户组下的所有角色
export const getRoleGroup = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/get/roles',
    data: data,
    method: 'post'
  })
}

// 更新用户组角色
export const changeRoleGroup = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/role/change',
    data: data,
    method: 'post'
  })
}

// 获取用户组下的所有用户
export const getUserGroup = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/get/users',
    data: data,
    method: 'post'
  })
}

// 批量添加用户组用户
export const addUserGroup = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/users/add',
    data: data,
    method: 'post'
  })
}

// 批量删除用户组用户
export const delUserGroup = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/users/del',
    data: data,
    method: 'post'
  })
}

// 创建用户组
export const createGroup = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/create',
    data: data,
    method: 'post'
  })
}

// 修改用户组
export const updateGroup = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/group/update',
    data: data,
    method: 'post'
  })
}

// 删除用户组
export const delGroup = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/group/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 显示权限表
export const getPermsList = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/action/list',
    data: data,
    method: 'post'
  })
}

// 修改权限信息
export const updateAction = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/action/update',
    data: data,
    method: 'post'
  })
}

// 字典列表
export const getDictTable = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/dict/list',
    data: data,
    method: 'post'
  })
}

// 创建字典
export const createDict = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/dict/create',
    data: data,
    method: 'post'
  })
}

// 修改字典
export const updateDict = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/dict/update',
    data: data,
    method: 'post'
  })
}
// 删除字典元素
export const delDict = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/dict/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 角色列表
export const getRoleList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/list',
    data: data,
    method: 'post'
  })
}

// 获取角色权限
export const getPermsRole = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/get/perms',
    data: data,
    method: 'post'
  })
}

// 更新角色权限
export const changePermsRole = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/perms/change',
    data: data,
    method: 'post'
  })
}

// 创建角色
export const createRole = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/create',
    data: data,
    method: 'post'
  })
}

// 修改角色
export const updateRole = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/update',
    data: data,
    method: 'post'
  })
}

// 删除角色
export const delRole = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/role/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 获取角色下的所有用户
export const getRoleUser = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/get/users',
    data: data,
    method: 'post'
  })
}

// 批量添加角色用户
export const addUserRole = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/users/add',
    data: data,
    method: 'post'
  })
}

// 批量删除角色用户
export const delUserRole = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/role/users/del',
    data: data,
    method: 'post'
  })
}

// 获取组织列表
export const getOrgTable = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/org/list',
    data: data,
    method: 'post'
  })
}

// 获取组织信息
export const getOrgInfo = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/org/get',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 修改组织logo
export const updateOrgLogo = (id, type, file) => {
  var data = new FormData() // 创建form对象
  data.append('id', id)// 通过append向form对象添加数据
  data.append('type', type)
  data.append('file', file)
  return axios.request({
    url: constant.ajaxurl + '/org/update/logo',
    data: data,
    // headers: {'Authorization': 'Bearer' + getToken()},
    method: 'post'
  })
}

// 修改组织信息
export const updateOrg = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/org/update',
    data: data,
    method: 'post'
  })
}

// 获取组织下的所有用户
export const getOrgUsers = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/org/get/users',
    data: data,
    method: 'post'
  })
}

// 批量添加组织用户
export const OrgUsersAdd = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/org/users/add',
    data: data,
    method: 'post'
  })
}

// 批量删除组织用户
export const OrgUsersDel = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/org/users/del',
    data: data,
    method: 'post'
  })
}

// 获取公告信息
export const getNotice = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/notice/get',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 获取公告表
export const getNoticeList = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/notice/list',
    data: data,
    method: 'post'
  })
}

// 创建公告表
export const createNotice = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/notice/create',
    data: data,
    method: 'post'
  })
}

// 修改公告表
export const updateNotice = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/notice/update',
    data: data,
    method: 'post'
  })
}

// 删除公告
export const delNotice = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/notice/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 部署工作流
export const WorkflowDeploy = (x, y, z, t) => {
  var data = new FormData() // 创建form对象
  data.append('file', x)// 通过append向form对象添加数据
  data.append('name', y)
  data.append('category', z)
  data.append('key', t)
  return axios.request({
    url: constant.ajaxurl + '/workflow/deploy',
    data: data,
    method: 'post'
  })
}

// 获取工作流部署列表
export const getWorkList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/deployment/list',
    data: data,
    method: 'post'
  })
}

// 删除工作流部署信息
export const delWorkflow = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/deployment/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 获取工作流实例列表
export const WorkflowInstanceList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/list/instance',
    data: data,
    method: 'post'
  })
}

// 删除工作流实例信息
export const delWorkflowInstance = (processInstanceId, deleteReason) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/delete/' + processInstanceId,
    params: {
      'deleteReason': deleteReason
    },
    method: 'post'
  })
}

// 获取工作流部署资源列表
export const getWorkResource = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/deployment/resource/list',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 获取工作流部署资源内容
export const findWorkResource = (x, y) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/deployment/resource/find',
    params: {
      'deploymentId': x,
      'resourceName': y
    },
    method: 'get'
  })
}

// 获取工作流定义列表
export const getWorkDefinition = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/definition/list',
    data: data,
    method: 'post'
  })
}

// 获取工作流历史审批列表
export const WorkflowHistoryList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/list/history',
    data: data,
    method: 'post'
  })
}

// 激活工作流定义
export const activateWorkDefinition = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/definition/activate',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 挂起工作流定义
export const suspendWorkDefinition = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/workflow/definition/suspend',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 注册短信配置信息
export const registerSmsConfig = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/register',
    data: data,
    method: 'post'
  })
}

// 短信配置信息
export const getSmsConfig = () => {
  return axios.request({
    url: constant.ajaxurl + '/sms/config/get',
    params: {},
    method: 'get'
  })
}

// 批量发送短信
export const smsSendBatch = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/sendBatch',
    params: data,
    method: 'post'
  })
}

// 更新短信配置信息
export const updateSmsConfig = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/config/update',
    data: data,
    method: 'post'
  })
}

// 短信模板列表
export const SmsTemplateList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/template/list',
    data: data,
    method: 'post'
  })
}

// 短信套餐列表
export const SmsPackageList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/package/list',
    data: data,
    method: 'post'
  })
}

// 购买短信套餐
export const SmsBuyCreate = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/buy/create',
    params: data,
    method: 'get'
  })
}

// 取消购买短信套餐
export const SmsCancelBuy = (id) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/buy/cancel',
    params: {
      'id': id
    },
    method: 'get'
  })
}

// 短信套餐购买列表
export const SmsBuyList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/buy/list',
    data: data,
    method: 'post'
  })
}

// 获取商品id
export const SmsBuyPay = (id) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/buy/pay',
    params: {
      'id': id
    },
    method: 'get'
  })
}

// 查看购买情况
export const SmsBuyGet = (id) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/buy/get',
    params: {
      'id': id
    },
    method: 'get'
  })
}

// 创建短信模板
export const createSmsTemplate = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/template/create',
    data: data,
    method: 'post'
  })
}

// 修改短信模板
export const updateSmsTemplate = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/template/update',
    data: data,
    method: 'post'
  })
}

// 删除短信模板
export const delSmsTemplate = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/template/delete',
    params: {
      'templateId': x
    },
    method: 'get'
  })
}

// 短信发送列表
export const getSmsSend = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/send/list',
    data: data,
    method: 'post'
  })
}

// 短信回复列表
export const getSmsReply = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/reply/list',
    data: data,
    method: 'post'
  })
}

// 统计短信发送量
export const SmsSendChart = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/sms/send/chart/mobile',
    data: data,
    method: 'post'
  })
}

// 公众号列表
export const WxMpList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/info/list',
    data: data,
    method: 'post'
  })
}

// 支付平台列表
export const WxPayInfoList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/pay/info/list',
    data: data,
    method: 'post'
  })
}

// 创建支付平台
export const createWxPay = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/pay/info/create',
    data: data,
    method: 'post'
  })
}

// 修改支付平台
export const updateWxPay = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/pay/info/update',
    data: data,
    method: 'post'
  })
}

// 删除支付平台
export const delWxPay = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/pay/info/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 获取扫描支付二维码链接
export const WxScanPay = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/pay/scanPay/qrcodeLink',
    params: data,

    method: 'get'
  })
}

// 删除公众号
export const delWxMp = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/info/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 创建公众号
export const createWxMp = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/info/create',
    data: data,
    method: 'post'
  })
}

// 修改公众号
export const updateWxMp = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/info/update',
    data: data,
    method: 'post'
  })
}

// 公众号自定义菜单查询
export const WxMpMenu = (x) => {
  var appid = x
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/menu/get?appid=' + appid + '',
    method: 'get'
  })
}

// 公众号自定义菜单创建、修改、删除
export const WxMpMenuCreate = (x, y) => {
  var appid = x
  const data = {
    'buttons': y
  }
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/menu/create?appid=' + appid + '',
    data: data,
    method: 'post'
  })
}

// 公众号素材列表
export const WxMpMaterial = (x, y) => {
  var appid = x
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/material/batchget?appid=' + appid + '',
    params: y,
    method: 'get'
  })
}

// 将公众号的用户同步到自己系统
export const syncWxMpUser = (x) => {
  var appid = x
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/user/sync',
    params: {
      'appid': appid
    },
    method: 'get'
  })
}

// 公众号用户管理
export const getWxMpUser = (x) => {
  var appid = x
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/user/get',
    params: {
      'appid': appid
    },
    method: 'get'
  })
}

// 公众号用户列表
export const WxMpUserList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/wx/mp/user/list',
    data: data,
    method: 'post'
  })
}

// // 公众号批量获取用户信息
// export const WxMpUserBatchget = (x) => {
//   var appid = x
//   return axios.request({
//     url: constant.ajaxurl + '/wx/mp/user/info/batchget?appid=${' + appid + '}',
//     params: {
//       'appid': appid
//     },
//     method: 'get'
//   })
// }

// 自定义表单配置信息
export const getCmsConfig = () => {
  return axios.request({
    url: constant.ajaxurl + '/cms/config/get',
    params: {},
    method: 'get'
  })
}

// 注册自定义表单配置信息
export const registerCmsConfig = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/register',
    data: data,
    method: 'post'
  })
}

// 获取表格列表
export const CmsTableList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/table/list',
    data: data,
    method: 'post'
  })
}

// 删除表格
export const delCmsTable = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/table/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 创建表格
export const createCmsTable = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/table/create',
    data: data,
    method: 'post'
  })
}

// 数据列列表
export const cmsColumnList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/column/list',
    data: data,
    method: 'post'
  })
}

// 新增数据列
export const createCmsColumn = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/column/create',
    data: data,
    method: 'post'
  })
}

// 修改数据列
export const updateCmsColumn = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/column/update',
    data: data,
    method: 'post'
  })
}

// 删除数据列
export const delColumn = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/column/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 行数据列表
export const cmsRowList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/row/list',
    data: data,
    method: 'post'
  })
}

// 插入行数据
export const createCmsRow = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/row/create',
    data: data,
    method: 'post'
  })
}

// 更新行数据
export const updateCmsRow = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/cms/row/update',
    data: data,
    method: 'post'
  })
}

// 删除行数据
export const delCmsRow = (x, y) => {
  const data = {
    'id': x,
    'tableName': y
  }
  return axios.request({
    url: constant.ajaxurl + '/cms/row/delete',
    data: data,
    method: 'post'
  })
}

// 上传文件
export const uploadCmsFile = (x) => {
  var data = new FormData() // 创建form对象
  data.append('file', x.file)
  return axios.request({
    url: constant.ajaxurl + '/cms/file/upload',
    data: data,
    method: 'post'
  })
}

// 车辆品牌列表
export const carBrandList = (x) => {
  // const data = JSON.parse(x)
  return axios.request({
    url: constant.ajaxurl + '/car/list/brand',
    data: x,
    method: 'post'
  })
}

// 获取车辆品牌信息
export const carBrandGet = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brand/get',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 新增车辆品牌
export const carBrandCreate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brand/create',
    data: data,
    method: 'post'
  })
}

// 更新车辆品牌
export const carBrandUpdate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brand/update',
    data: data,
    method: 'post'
  })
}

// 删除车辆品牌
export const carBrandDel = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brand/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 车辆制造商列表
export const carBrandMfList = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/list/brandMf',
    data: x,
    method: 'post'
  })
}

// 获取车辆制造商信息
export const carBrandMfGet = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brandMf/get',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 新增车辆制造商
export const carBrandMfCreate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brandMf/create',
    data: data,
    method: 'post'
  })
}

// 更新车辆制造商
export const carBrandMfUpdate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brandMf/update',
    data: data,
    method: 'post'
  })
}

// 删除车辆制造商
export const carBrandMfDel = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brandMf/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 车辆系列列表
export const carBrandAudiList = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/list/brandAudi',
    data: x,
    method: 'post'
  })
}

// 获取车辆系列信息
export const carBrandAudiGet = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brandAudi/get',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 新增车辆系列
export const carBrandAudiCreate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brandAudi/create',
    data: data,
    method: 'post'
  })
}

// 更新车辆系列
export const carBrandAudiUpdate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brandAudi/update',
    data: data,

    method: 'post'
  })
}

// 删除车辆系列
export const carBrandAudiDel = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brandAudi/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 车辆型号列表
export const carBrandVersionList = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/list/brandVersion',
    data: x,
    method: 'post'
  })
}

// 获取车辆型号信息
export const carBrandVersionGet = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brandVersion/get',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 新增车辆型号
export const carBrandVersionCreate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brandVersion/create',
    data: data,
    method: 'post'
  })
}

// 更新车辆型号
export const carBrandVersionUpdate = (x) => {
  const data = x
  return axios.request({
    url: constant.ajaxurl + '/car/brandVersion/update',
    data: data,
    method: 'post'
  })
}

// 删除车辆型号
export const carBrandVersionDel = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/car/brandVersion/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 服务器列表
export const ServerList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/server/list',
    data: data,
    method: 'post'
  })
}

// 获取服务器信息
export const getServer = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/server/get',
    data: data,
    method: 'post'
  })
}

// 创建服务器
export const createServer = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/server/create',
    data: data,
    method: 'post'
  })
}

// 修改服务器
export const updateServer = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/server/update',
    data: data,
    method: 'post'
  })
}

// 删除服务器
export const delServer = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/server/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 已安装服务列表
export const haveServerList = (data) => {
  var url = constant.ajaxurl + '/isp/app/list'
  return axios.request({
    url: url,
    params: data,
    method: 'GET'
  })
}

// 安装服务
export const installServer = (data) => {
  var url = constant.ajaxurl + '/isp/app/install'
  return axios.request({
    url: url,
    params: data,
    method: 'GET'
  })
}

// 安装LDAP服务
export const LdapInstall = (data) => {
  var url = constant.ajaxurl + '/isp/app/install'
  return axios.request({
    url: url,
    data: data,
    method: 'post'
  })
}

// 创建LDAP账户组
export const createLdapGroup = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/ldap/group/create',
    data: data,
    method: 'post'
  })
}

// 更新LDAP账户组
export const updateLdapGroup = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/ldap/group/update',
    data: data,
    method: 'post'
  })
}

// 删除LDAP账户组
export const delLdapGroup = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/ldap/group/delete',
    params: data,
    method: 'get'
  })
}
// LDAP账户组列表
export const LdapGroupList = (data) => {
  var url = constant.ajaxurl + '/isp/ldap/group/list'
  return axios.request({
    url: url,
    params: data,
    method: 'GET'
  })
}

// 安装Samba服务
export const SambaInstall = (data) => {
  var url = constant.ajaxurl + '/isp/app/install'
  return axios.request({
    url: url,
    data: data,
    method: 'post'
  })
}

// 创建LDAP系统账户
export const createLdapAccount = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/ldap/account/create',
    data: data,
    method: 'post'
  })
}

// 修改LDAP系统账户
export const updateLdapAccount = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/ldap/account/update',
    data: data,
    method: 'post'
  })
}

// 修改LDAP系统账户密码
export const updateLdapAccountPwd = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/ldap/account/password',
    params: data,
    method: 'get'
  })
}

// 删除LDAP系统账户
export const delLdapAccount = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/ldap/account/delete',
    params: data,
    method: 'get'
  })
}
// LDAP系统账户列表
export const LdapAccountList = (data) => {
  var url = constant.ajaxurl + '/isp/ldap/account/list'
  return axios.request({
    url: url,
    data: data,
    method: 'post'
  })
}

// 显示所有LDAP用户
export const LdapUserAll = () => {
  return axios.request({
    url: constant.ajaxurl + '/ldap/user/findAll',
    method: 'get'
  })
}

// Samba虚拟目录列表
export const SmbVdirList = (data) => {
  var url = constant.ajaxurl + '/isp/smb/vdir/list'
  return axios.request({
    url: url,
    data: data,
    method: 'post'
  })
}

// 获取Samba虚拟目录
export const getSmbVdir = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/smb/vdir/get',
    params: data,
    method: 'GET'
  })
}

// 创建Samba虚拟目录
export const createSmbVdir = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/smb/vdir/create',
    data: data,
    method: 'post'
  })
}

// 修改Samba虚拟目录
export const updateSmbVdir = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/smb/vdir/update',
    data: data,
    method: 'post'
  })
}

// 删除Samba虚拟目录
export const delSmbVdir = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/smb/vdir/delete',
    params: {
      'id': x
    },
    method: 'GET'
  })
}

// 启动服务
export const startServer = (serverId, name) => {
  var url = constant.ajaxurl + '/isp/app/start'
  return axios.request({
    url: url,
    params: {
      'serverId': serverId,
      'name': name
    },
    method: 'GET'
  })
}

// 停止服务
export const stopServer = (serverId, name) => {
  var url = constant.ajaxurl + '/isp/app/stop'
  return axios.request({
    url: url,
    params: {
      'serverId': serverId,
      'name': name
    },
    method: 'GET'
  })
}

// 重启服务
export const restartServer = (serverId, name) => {
  var url = constant.ajaxurl + '/isp/app/restart'
  return axios.request({
    url: url,
    params: {
      'serverId': serverId,
      'name': name
    },
    method: 'GET'
  })
}

// 查看服务状态
export const statusServer = (serverId, name) => {
  var url = constant.ajaxurl + '/isp/app/status'
  return axios.request({
    url: url,
    params: {
      'serverId': serverId,
      'name': name
    },
    method: 'GET'
  })
}

// 域名列表
export const DomainList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/list',
    data: data,
    method: 'post'
  })
}

// 获取域名信息
export const getDomain = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/get',
    data: data,
    method: 'post'
  })
}

// 创建域名
export const createDomain = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/create',
    data: data,
    method: 'post'
  })
}

// 修改域名
export const updateDomain = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/update',
    data: data,
    method: 'post'
  })
}

// 删除域名
export const delDomain = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 安装SSL证书
export const createSSL = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/ssl/create',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 安装SQL服务
export const createSQL = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/sql/create',
    data: data,
    method: 'post'
  })
}

// 安装CMS服务
export const createCMS = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/isp/domain/cms/create',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// 新增素材
export const uploadMedia = (x) => {
  var data = new FormData() // 创建form对象
  var type = x.type
  data.append('type', x.type)
  data.append('title', x.title)
  if (type < 4) {
    data.append('file', x.file)
  } else {
    data.append('content', x.content)
  }
  return axios.request({
    url: constant.ajaxurl + '/media/upload',
    data: data,
    method: 'post'
  })
}

// 图文素材列表
export const NewsList = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/news/list',
    data: data,
    method: 'post'
  })
}

// 创建图文素材
export const createNews = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/news/create',
    data: data,
    method: 'post'
  })
}

// 获取图文素材
export const getNews = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/news/get',
    params: data,
    method: 'get'
  })
}

// 发送图文素材
export const sendNews = (data) => {
  return axios.request({
    url: constant.ajaxurl + '/news/send',
    params: data,
    method: 'get'
  })
}

// 删除图文素材
export const delNews = (x) => {
  return axios.request({
    url: constant.ajaxurl + '/news/delete',
    params: {
      'id': x
    },
    method: 'get'
  })
}

// export const getTableData = () => {
//   return axios.request({
//     url: 'get_table_data',
//     method: 'get'
//   })
// }
//
// export const getDragList = () => {
//   return axios.request({
//     url: 'get_drag_list',
//     method: 'get'
//   })
// }
//
// export const errorReq = () => {
//   return axios.request({
//     url: 'error_url',
//     method: 'post'
//   })
// }
//
// export const saveErrorLogger = info => {
//   return axios.request({
//     url: 'save_error_logger',
//     data: info,
//     method: 'post'
//   })
// }
//
// export const uploadImg = formData => {
//   return axios.request({
//     url: 'image/upload',
//     data: formData
//   })
// }
//
// export const getOrgData = () => {
//   return axios.request({
//     url: 'get_org_data',
//     method: 'get'
//   })
// }
