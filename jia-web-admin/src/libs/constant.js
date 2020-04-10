import store from '@/store'
import { carBrandList, carBrandMfList, carBrandAudiList, carBrandVersionList, getWorkToken, getUser } from '@/api/data'
// import router from '@/router'

var constant = (function () {
  var sc = {}
  // 菜单
  sc.MenuArr = [{
    id: 'sms',
    icon: 'md-mail',
    str: '短信服务'
  }, {
    id: 'wechat',
    icon: 'ios-chatbubbles',
    str: '微信服务'
  }, {
    id: 'workflow',
    icon: 'md-infinite',
    str: '流程服务'
  }, {
    id: 'server',
    icon: 'ios-desktop',
    str: '服务器管理'
  }, {
    id: 'cms',
    icon: 'md-albums',
    str: '内容管理'
  }, {
    id: 'car',
    icon: 'md-car',
    str: '车型管理'
  }, {
    id: 'system',
    icon: 'md-settings',
    str: '系统设置'
  }, {
    id: 'materials',
    icon: 'md-copy',
    str: '单页面应用'
  }, {
    id: 'other',
    icon: 'ios-keypad',
    str: '更多服务'
  }]
  // 文档菜单
  sc.DocMenuArr = [ {
    id: 1,
    icon: 'ios-clipboard',
    url: 'https://doc.jia.wydiy.com/web/#/1',
    str: '+顺服务技术文档'
  }, {
    id: 2,
    icon: 'ios-car',
    url: 'https://doc.jia.wydiy.com/web/#/2?page_id=200',
    str: '+顺车辆技术文档'
  }, {
    id: 3,
    icon: 'ios-folder',
    url: 'https://doc.jia.wydiy.com/web/#/4?page_id=223',
    str: '+顺办公技术文档'
  // }, {
  //   id: 4,
  //   icon: 'ios-build',
  //   url: 'https://doc.jia.wydiy.com/web/#/5?page_id=236',
  //   str: '+顺网站建设'
  }]
  // 权限级别
  sc.ActionLevel = [ {
    id: 1,
    str: '总平台'
  }, {
    id: 2,
    str: '企业平台'
  }]
  // 工作流定义状态
  sc.WorkDfStaus = [ {
    id: 1,
    str: '有效'
  }, {
    id: 2,
    str: '挂起'
  }]
  // 角色使用状态
  sc.RoleStatus = [ {
    id: 0,
    str: '停用'
  }, {
    id: 1,
    str: '正常'
  }]
  // 短信模板类型
  sc.SmsTemplateType = [ {
    id: 1,
    str: '短信验证码'
  }, {
    id: 2,
    str: '待办任务'
  }]
  // 短信模板状态
  sc.SmsTemplateStatus = [ {
    id: 1,
    str: '有效'
  }, {
    id: 0,
    str: '无效'
  }]
  // 短信支付状态
  sc.SmsBuyStatus = [ {
    id: 0,
    str: '未支付'
  }, {
    id: 1,
    str: '已支付'
  }, {
    id: 2,
    str: '已取消'
  }]
  // 微信支付平台状态
  sc.WxPayStatus = [ {
    id: 0,
    str: '无效'
  }, {
    id: 1,
    str: '有效'
  }]
  // 公众号类别
  sc.WxMpLever = [ {
    id: 1,
    str: '普通订阅号'
  }, {
    id: 2,
    str: '普通服务号'
  }, {
    id: 3,
    str: '认证订阅号'
  }, {
    id: 4,
    str: '认证服务号/认证媒体/政府订阅号'
  }]
  // cms表格是否为空
  sc.CmsTableNotNull = [ {
    id: 1,
    str: '非空'
  }, {
    id: 0,
    str: '可空'
  }]
  // cms表格是否为搜索条件
  sc.CmsTableIsSearch = [ {
    id: 1,
    str: '是'
  }, {
    id: 0,
    str: '否'
  }]
  // cms表格是否列表显示
  sc.CmsTableIsList = [ {
    id: 1,
    str: '是'
  }, {
    id: 0,
    str: '否'
  }]
  // 服务器状态
  sc.ServerStatus = [ {
    id: 0,
    str: '无效'
  }, {
    id: 1,
    str: '有效'
  }]
  // 服务状态
  sc.ServerAppStatus = [ {
    id: -1,
    str: '未安装'
  }, {
    id: 0,
    str: '未启动'
  }, {
    id: 1,
    str: '已启动'
  }]
  // 域名类型
  sc.DnsType = [ {
    id: 'aly',
    str: '阿里云'
  }, {
    id: 'txy',
    str: '腾讯云'
  }, {
    id: 'gdd',
    str: 'Godaddy'
  }]
  // 是否安装HTTPS证书
  sc.sslFlag = [ {
    id: 0,
    str: '否'
  }, {
    id: 1,
    str: '是'
  }]
  // 是否提供管理后台
  sc.adminFlag = [ {
    id: 0,
    str: '否'
  }, {
    id: 1,
    str: '是'
  }]
  // 是否提供企业邮箱服务
  sc.mailboxService = [ {
    id: 0,
    str: '否'
  }, {
    id: 1,
    str: '是'
  }]
  // 是否提供HTTP服务
  sc.hostService = [ {
    id: 0,
    str: '否'
  }, {
    id: 1,
    str: '是'
  }]
  // HTTP服务类型
  sc.hostType = [ {
    id: 1,
    str: 'HTML'
  }, {
    id: 2,
    str: 'JAVA'
  }, {
    id: 3,
    str: 'PHP'
  }]
  // 是否安装SQL服务
  sc.sqlService = [ {
    id: 0,
    str: '否'
  }, {
    id: 1,
    str: '是'
  }]
  // 是否提供CMS服务
  sc.cmsFlag = [ {
    id: 0,
    str: '否'
  }, {
    id: 1,
    str: '是'
  }]
  // 文件类型
  sc.FileType = [ {
    id: 1,
    str: '公司LOGO'
  }, {
    id: 2,
    str: '用户头像'
  }, {
    id: 3,
    str: '公告图片'
  }]
  // 微信用户性别
  sc.WxUserSex = [ {
    id: 0,
    str: '无记录'
  }, {
    id: 1,
    str: '男'
  }, {
    id: 2,
    str: '女'
  }]
  sc.getvalue = function (name, value) {
    var item = this[name]
    for (var i = 0; i < item.length; i++) {
      if (!(typeof value === 'string' && value === '') && item[i].id === value) { return item[i].str }
    }
  }
  // 配置ajax的访问链接token参数
  sc.ajaxurl = 'https://apia.jia.wydiy.com'
  // sc.workajaxurl = 'https://api.jia.wydiy.com'
  // 配置获取静态文件的url
  sc.StaticUrl = 'https://api.jia.wydiy.com/file/res/'
  sc.NewStaticUrl = 'https://apia.jia.wydiy.com/file/res/'
  sc.grant_type = 'client_credentials'
  sc.client_id = 'jia_client'
  sc.client_secret = 'jia_secret'
  // 将URI转换为文件
  sc.dataURLtoFile = function (dataurl, filename) {
    var arr = dataurl.split(',')
    var mime = arr[0].match(/:(.*?);/)[1]
    var bstr = decodeURIComponent(arr[1])
    // var n = bstr.length
    // var u8arr = new Uint8Array(n)
    // while (n--) {
    //   u8arr[n] = bstr.charCodeAt(n)
    // }
    return new File([bstr], filename, { type: mime })
  }
  // 验证规则
  sc.verifyArr = {}
  // 非空验证
  sc.verifyArr.NotNull = (rule, y, callback) => {
    if (y === '') {
      return callback(new Error('该项为必填项'))
    } else {
      callback()
    }
  }
  // 验证是否为数字
  sc.verifyArr.NotInt = (rule, y, callback) => {
    var num = y
    if (isNaN(num) === true) {
      return callback(new Error('请填写数字'))
    } else {
      callback()
    }
  }
  // 验证是否为手机
  sc.verifyArr.NotMobile = (rule, y, callback) => {
    var mobile = y
    var reg = /^1[34578][0-9]{9}$/ // 验证规则
    if (!mobile) { callback() } else if (reg.test(mobile) === false) {
      return callback(new Error('手机号码不符合规范'))
    } else {
      callback()
    }
  }
  // 验证是否为邮箱
  sc.verifyArr.NotEmail = (rule, y, callback) => {
    var email = y
    var reg = /^[A-Za-z\d]+([-_.][A-Za-z\d]+)*@([A-Za-z\d]+[-.])+[A-Za-z\d]{2,4}$/ // 验证规则
    if (!email) { callback() } else if (reg.test(email) === false) {
      return callback(new Error('邮箱地址不符合规范'))
    } else {
      callback()
    }
  }
  // 验证长度是否过长
  sc.verifyArr.NotLength = (rule, y, callback, source, options) => {
    var str = y
    var len = 0
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i)
      // 单字节加1
      if ((c >= 0x0001 && c <= 0x007e) || (c >= 0xff60 && c <= 0xff9f)) {
        len++
      } else {
        len += 2
      }
    }
    var key
    var s_len
    for (key in source) {
      var key_arr = key.split('_')
      s_len = parseInt(key_arr[key_arr.length - 1])
    }
    if (len > s_len) {
      return callback(new Error('文本长度过长'))
    } else {
      callback()
    }
  }
  // 生成验证集合
  sc.CreateVerify = function (x) {
    var arr = JSON.parse(x)
    var Verify = {}
    arr.forEach(function (val, index, array) {
      var name = val.name
      var method = val.method
      var methodArr = method.split(',')
      var obj = []
      methodArr.forEach(function (value, i) {
        var t_method = value
        var way_str = 'constant.verifyArr.' + t_method
        var way = eval(way_str)
        var obj_str = eval('[{ validator:' + way + ', trigger: \'blur\' }]')
        obj.push(obj_str[0])
      })
      Verify['key'] = obj
      Verify[name] = Verify['key']
      delete Verify['key']
    })
    return Verify
  }
  // 时间戳转日期时间
  sc.formatDate = function (timeStamp) {
    var date = new Date()
    date.setTime(timeStamp * 1000)
    var y = date.getFullYear()
    var m = date.getMonth() + 1
    m = m < 10 ? ('0' + m) : m
    var d = date.getDate()
    d = d < 10 ? ('0' + d) : d
    return y + '-' + m + '-' + d
  }
  // 日期格式字符串转日期时间
  sc.formatDateTime = function (time) {
    var d = new Date()
    d.setTime(time * 1000)
    var y = d.getFullYear()
    var m = d.getMonth() + 1
    m = m < 10 ? ('0' + m) : m
    var da = d.getDate()
    da = da < 10 ? ('0' + da) : da
    var h = d.getHours()
    h = h < 10 ? ('0' + h) : h
    var minute = d.getMinutes()
    minute = minute < 10 ? ('0' + minute) : minute
    var second = d.getSeconds()
    second = second < 10 ? ('0' + second) : second
    var times = y + '-' + m + '-' + da + ' ' + h + ':' + minute + ':' + second
    return times
  }
  // 日期格式字符串转时间戳
  sc.stampDateTime = function (time) {
    var times = new Date(time).getTime() / 1000
    return times
  }
  // js获取当前时间前后N天前后日期
  sc.GetDateStr = function (AddDayCount) {
    var dd = new Date()
    dd.setDate(dd.getDate() + AddDayCount)// 获取AddDayCount天后的日期
    var y = dd.getFullYear()
    var m = (dd.getMonth() + 1) < 10 ? '0' + (dd.getMonth() + 1) : (dd.getMonth() + 1)// 获取当前月份的日期，不足10补0
    var d = dd.getDate() < 10 ? '0' + dd.getDate() : dd.getDate()// 获取当前几号，不足10补0
    return y + '-' + m + '-' + d
  }
  // 获取自定义时间计算后时间
  sc.GetDataCount = function (e, AddDayCount) {
    var year = e.split('-')[0]
    var month = e.split('-')[1] - 1
    var day = e.split('-')[2]
    var date = new Date(year, month, day)
    date.setDate(date.getDate() + AddDayCount)
    var y = date.getFullYear()
    var m = (date.getMonth() + 1) < 10 ? '0' + (date.getMonth() + 1) : (date.getMonth() + 1)// 获取当前月份的日期，不足10补0
    var d = date.getDate() < 10 ? '0' + date.getDate() : date.getDate()// 获取当前几号，不足10补0
    return y + '-' + m + '-' + d
  }
  // 生成搜索条件
  sc.appendSearch = function (x) {
    var s_obj = x
    // 获取搜索列表中可枚举的属性
    var arr = Object.keys(s_obj)
    var obj = {}
    arr.forEach(function (val, i) {
      var key = val
      var value = s_obj[key]
      if (value) {
        obj[key] = value
      } else {}
    })
    return obj
  }
  // 获取车辆品牌
  sc.appendBrand = function (x, y) {
    const pageNum = 1
    var brandArr = []
    var data = {
      'pageNum': pageNum
    }
    carBrandList(data).then(res => {
      var r_data = res.data.data
      r_data.forEach((val, i) => {
        var brandId = val.id
        var brand = val.brand
        var obj = { 'id': brandId, 'value': brand }
        brandArr.push(obj)
      })
      x[y] = brandArr
    })
  }
  // 获取车辆制作商
  sc.appendBrandMf = function (x, y, z) {
    var z_arr = ''
    if (z) {
      z.forEach((val, i) => {
        var key = val.key
        var value = val.value
        z_arr = '"' + key + '":"' + value + '",' + z_arr
      })
    } else {}
    var result = (z === '') ? '{}' : '{' + constant.removeLastChar(z_arr) + '}'
    const pageNum = 1
    var brandMfArr = []
    var data = {
      'pageNum': pageNum,
      'search': result
    }
    carBrandMfList(data).then(res => {
      var r_data = res.data.data
      r_data.forEach((val, i) => {
        var brandMfId = val.id
        var brandMf = val.brandMf
        var obj = { 'id': brandMfId, 'value': brandMf }
        brandMfArr.push(obj)
      })
      x[y] = brandMfArr
    })
  }
  // 获取车辆系列
  sc.appendBrandAudi = function (x, y, z) {
    var z_arr = ''
    if (z) {
      z.forEach((val, i) => {
        var key = val.key
        var value = val.value
        z_arr = '"' + key + '":"' + value + '",' + z_arr
      })
    } else {}
    var result = (z === '') ? '{}' : '{' + constant.removeLastChar(z_arr) + '}'
    const pageNum = 1
    var brandAudiArr = []
    var data = {
      'pageNum': pageNum,
      'search': result
    }
    carBrandAudiList(data).then(res => {
      var r_data = res.data.data
      r_data.forEach((val, i) => {
        var brandAudiId = val.id
        var brandAudi = val.carSeries
        var obj = { 'id': brandAudiId, 'value': brandAudi }
        brandAudiArr.push(obj)
      })
      x[y] = brandAudiArr
    })
  }
  // 获取车辆型号
  sc.appendBrandVersion = function (x, y, z) {
    var z_arr = ''
    if (z) {
      z.forEach((val, i) => {
        var key = val.key
        var value = val.value
        z_arr = '"' + key + '":"' + value + '",' + z_arr
      })
    } else {}
    var result = (z === '') ? '{}' : '{' + constant.removeLastChar(z_arr) + '}'
    const pageNum = 1
    var brandVersionArr = []
    var data = {
      'pageNum': pageNum,
      'search': result
    }
    carBrandVersionList(data).then(res => {
      var r_data = res.data.data
      r_data.forEach((val, i) => {
        var brandVersionId = val.id
        var brandVersion = val.vName
        var obj = { 'id': brandVersionId, 'value': brandVersion }
        brandVersionArr.push(obj)
      })
      x[y] = brandVersionArr
    })
  }
  // 获取用户信息
  sc.getUser = function (data) {
    return new Promise((resolve, reject) => {
      try {
        getUser(data).then(res => {
          resolve(res)
        }).catch(err => {
          reject(err)
        })
      } catch (error) {
        reject(error)
      }
    })
  }
  // 去除最后一个逗号
  sc.removeLastChar = function (string) {
    var n = string.lastIndexOf(',')
    var str = string.substring(0, n)
    return str
  }
  // 获取工作流token(含同步axios方法)
  // 方法一
  // sc.getWorkToken = function () {
  //   return getWorkToken()
  // }
  // 方法二
  // sc.getWorkToken = async function () {
  //   var result = await getWorkToken()
  //   return result
  // }
  // 方法三
  sc.getWorkToken = function () {
    return new Promise((resolve, reject) => {
      getWorkToken().then(res => {
        const content = res.data
        resolve(content)
      })
    })
  }
  // 仿jq的append()方法
  sc.append = function (parent, text) {
    if (typeof text === 'string') {
      var temp = document.createElement('div')
      temp.innerHTML = text
      // 防止元素太多 进行提速
      var frag = document.createDocumentFragment()
      while (temp.firstChild) {
        frag.appendChild(temp.firstChild)
      }
      parent.appendChild(frag)
    } else {
      parent.appendChild(text)
    }
  }
  // 权限控制
  sc.findRole = function (name) {
    var hasRole = store.state.user.access
    var res = false
    if (hasRole && hasRole.findIndex(v => v === name) > -1) {
      res = true
    } else {}
    return res
  }
  // width、height调用时传入具体像素值，控制大小 ,不传则默认图像大小
  sc.getBase64Image = function (img, width, height) {
    var canvas = document.createElement('canvas')
    canvas.width = width || img.width
    canvas.height = height || img.height
    var ctx = canvas.getContext('2d')
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    var dataURL = canvas.toDataURL()
    return dataURL
  }
  sc.getCanvasBase64 = function (img) {
    var image = new Image()
    // 至关重要
    image.crossOrigin = ''
    image.src = img
    // 至关重要
    var deferred = $.Deferred()
    if (img) {
      image.onload = function () {
        deferred.resolve(constant.getBase64Image(image))// 将base64传给done上传处理
      }
      return deferred.promise()// 问题要让onload完成后再return sessionStorage['imgTest']
    }
  }
  return sc
})()
export default constant
