<template>
  <div>
    <Card>
      <Form inline
            :label-width="80"
            class="search_form">
        <div class="search_div">
          <div v-for="(item,index) in SearchList.items"
               :key="index"
               class="search_chil_div">
            <div v-if="item.type==='input'">
              <FormItem :label="item.title">
                <Input v-model="item.value"></Input>
              </FormItem>
            </div>
            <div v-else-if="item.type==='date'">
              <FormItem :label="item.title">
                <DatePicker type="date"
                            placeholder=""
                            :value="item.value"
                            @on-change="item.value=$event"
                            format="yyyy-MM-dd"></DatePicker>
              </FormItem>
            </div>
            <div v-else-if="item.type==='datetime'">
              <FormItem :label="item.title">
                <DatePicker type="datetime"
                            placeholder=""
                            :value="item.value"
                            @on-change="item.value=$event"
                            format="yyyy-MM-dd HH:mm:ss"></DatePicker>
              </FormItem>
            </div>
            <div v-else>
              <FormItem :label="item.title">
                <Select v-model="item.value"
                        clearable
                        class="search_select">
                  <Option v-for="(option, v) in item.selectRange"
                          :value="option.code"
                          :key="v">{{option.value}}</Option>
                </Select>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button @click="searchTable(1)">查询</Button>
        <Button type="success"
                @click="handleCreate">增加数据</Button>
      </div>
      <div>
        <tables ref="tables"
                border
                v-model="tableData"
                :columns="columns"
                @ChangPage=" ParentChange" />
      </div>
      <!--<Button style="margin: 10px 0;" type="primary" @click="exportExcel">导出为Csv文件</Button>-->
    </Card>
    <ready ref="ready"
           @Next="ParentNext"></ready>
    <row-info ref="RowInfo"
              @operate="ParentOperate"></row-info>
  </div>
</template>
<script>

import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import RowInfo from '../modal/table-row-info'
import { getToken } from '@/libs/util'
import { cmsColumnList, cmsRowList, createCmsRow, updateCmsRow, delCmsRow } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    RowInfo
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 20,
      columns: [
        {
          'title': 'Name',
          'key': 'name'
        }
      ],
      SearchList: {
        items: []
      },
      tableData: [],
      columnsData: [],
      longtextArr: [],
      imageArr: []
    }
  },
  methods: {
    // exportExcel () {
    //   this.$refs.tables.exportCsv({
    //     filename: `table-${(new Date()).valueOf()}.csv`
    //   })
    // },
    // 获取列数据
    getColumnList () {
      const pageNum = this.pageNum
      const pageSize = this.pageSize
      const tableId = this.$route.query.tableId
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': { 'tableId': tableId }
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var _this = this
      cmsColumnList(data).then(res => {
        this.columnsData = res.data.data
        var longtextArr = this.longtextArr
        var imageArr = this.imageArr
        var datas = res.data.data
        var arr = []
        // 验证数组
        var v_arr = []
        datas.forEach(function (val, i) {
          var title = val.remark
          var key = val.name
          var selectRange = val.selectRange
          var type = val.type
          var precision = val.precision
          var notnull = val.notnull
          var isList = val.isList
          // 形成搜索条件
          var isSearch = val.isSearch
          if (type === 'longtext') {
            longtextArr.push(key)
          } else if (type === 'image') {
            imageArr.push(key + '_' + precision)
          }
          // 可作为搜索条件
          if (isSearch === 1) {
            // 判断是否需要做下拉
            var s_obj
            if (selectRange === null) {
              var sType
              if (type === 'date') {
                sType = type
              } else if (type === 'datetime') {
                sType = type
              } else {
                sType = 'input'
              }
              s_obj = {
                'title': title,
                'key': key,
                'value': '',
                'selectRange': selectRange,
                'type': sType
              }
              _this.SearchList.items.push(s_obj)
            } else {
              s_obj = {
                'title': title,
                'key': key,
                'value': '',
                'selectRange': JSON.parse(selectRange),
                'type': 'select'
              }
              _this.SearchList.items.push(s_obj)
            }
          } else { }// 不可作为搜索条件
          // 形成搜索条件end
          var obj
          var v_obj
          if (notnull === 1 && type !== 'longtext') {
            if (precision !== '' || precision !== null) {
              v_obj = { 'name': key + '_' + precision, 'method': 'NotNull,NotLength' }
            } else {
              v_obj = { 'name': key + '_' + precision, 'method': 'NotNull' }
            }
          } else {
            if (precision !== '' || precision !== null) {
              v_obj = { 'name': key + '_' + precision, 'method': 'NotLength' }
            } else { }
          }
          v_arr.push(v_obj)
          // 生成end
          if (selectRange === null) {
            // 生成表单、验证对象
            _this.$refs.RowInfo.RowInfo_from[key + '_' + precision] = ''
            // 生成表单、验证对象end
            var DomType
            if (type === 'date' || type === 'datetime' || type === 'image') {
              DomType = type
            } else {
              DomType = 'input'
            }
            obj = {
              'title': title,
              'key': key,
              'DomType': DomType,
              'RowType': type,
              'RowPrecision': precision,
              'RowNotnull': notnull,
              'RowIsList': isList,
              'RowSelectRange': selectRange,
              'className': 'show_second'
            }
            if (type === 'image') {
              obj.render = (h, params) => {
                var data = params.row
                if (data === 0) { } else { }// 防止语法提示错误，无作用
                var str = 'data.' + key
                var Estr = eval(str)
                if (Estr) {
                  return h('img', {
                    style: {
                      padding: '2px',
                      height: '42px'
                    },
                    attrs: {
                      src: _this.$constant.NewStaticUrl + Estr + '?access_token=' + getToken()
                    }
                  }, '')
                } else {
                  return ''
                }
              }
            }
          } else {
            var Sarr = JSON.parse(val.selectRange)
            // 生成表单、验证对象
            _this.$refs.RowInfo.RowInfo_from[key + '_' + precision] = Sarr[0].code
            // 生成表单、验证对象end
            var RowSelectRange = []
            Sarr.forEach(function (v, i) {
              var r_code = v.code
              var r_value = v.value
              var obj = { 'code': r_code, 'value': r_value }
              RowSelectRange.push(obj)
            })
            obj = {
              'title': title,
              'key': key,
              'DomType': 'select',
              'RowType': type,
              'RowPrecision': precision,
              'RowNotnull': notnull,
              'RowIsList': isList,
              'RowSelectRange': RowSelectRange,
              // 'className': 'show_second',
              render: (h, params) => {
                var data = params.row
                if (data === 0) { } else { }// 防止语法提示错误，无作用
                var str = 'data.' + key
                var Estr = eval(str)
                var result
                Sarr.forEach(function (val, i) {
                  var code = val.code
                  var value = val.value
                  if (code === '' + Estr) { result = value }
                })
                return h('span', result)
              }
            }
          }
          arr.push(obj)
        })
        // 生成模板表单
        this.$refs.RowInfo.RowInfoList.items = [...arr]

        // 去掉隐藏列
        arr = arr.filter(it => it.RowIsList === 1)

        var strArr = JSON.stringify(v_arr)
        this.$refs.RowInfo.ruleValidate = this.$constant.CreateVerify(strArr)
        var operate = {
          title: '操作',
          key: 'operate',
          width: 140,
          fixed: 'right',
          // className: 'show_s',
          render: (h, params) => {
            var s = params.row
            return h('div', {
              class: {
                table_operate: true
              }
            }, [
              h('Button', {
                props: {
                  type: 'info',
                  size: 'small'
                },
                on: {
                  click: () => {
                    this.handleUpdate(s)
                  }
                }
              }, '修改'),
              h('Button', {
                props: {
                  type: 'error',
                  size: 'small'
                },
                on: {
                  click: () => {
                    this.handleDelete(s)
                  }
                }
              }, '删除')
            ])
          }
        }
        arr.push(operate)
        this.columns.splice(0, 1)
        this.columns = arr
        // console.log(Carr)
        // console.log(this.$refs.RowInfo.RowInfo_from)
        // console.log(this.$refs.RowInfo.ruleValidate)
        // console.log(_this.SearchList.items)
      })
    },
    // 增加行数据
    handleCreate () {
      this.$refs.RowInfo.RowInfo_title = '增加行数据'
      this.$refs.RowInfo.RowInfo_judge = 'create'
      this.$refs.RowInfo.$refs.RowInfoList.resetFields()
      this.$refs.RowInfo.RowInfo_Id = ''
      var _this = this
      var longtextArr = _this.longtextArr
      longtextArr.forEach(function (value, i) {
        var obj = eval('_this.$refs.RowInfo.$refs.editor_' + value)
        obj[0].setHtml('')
      })
      var imageArr = _this.imageArr
      imageArr.forEach(function (value, i) {
        _this.$refs.RowInfo.RowInfo_from[value] = ''
        var imgFile = eval('_this.$refs.RowInfo.$refs.imgFile_' + value)
        imgFile[0].src = ''
        var imgFileDel = eval('_this.$refs.RowInfo.$refs.imgFileDel_' + value)
        imgFileDel[0].style.display = 'none'
      })
      this.$refs.RowInfo.RowInfo_modal = true
    },
    // 修改行数据
    handleUpdate (x) {
      this.$refs.RowInfo.RowInfo_title = '修改行数据'
      this.$refs.RowInfo.RowInfo_judge = 'update'
      this.$refs.RowInfo.$refs.RowInfoList.resetFields()
      this.$refs.RowInfo.RowInfo_Id = x.id
      var longtextArr = this.longtextArr
      var imageArr = this.imageArr
      // 将对象属性枚举
      var row_arr = Object.keys(x)
      var Cname_arr = Object.keys(this.$refs.RowInfo.RowInfo_from)
      var _this = this
      row_arr.forEach(function (val, i) {
        var row_name = val
        var row_value = x[row_name]
        // 一般赋值
        Cname_arr.forEach(function (value, i) {
          // 截取‘_’并移除最后一个数组元素
          var n_name_arr = value.split('_').slice(0, -1)
          // 拼接元素形成新键名
          var n_name = n_name_arr.join('_')
          if (row_name === n_name) {
            _this.$refs.RowInfo.RowInfo_from[value] = '' + row_value
          } else { }
        })
        longtextArr.forEach(function (value, i) {
          if (row_name === value) {
            var obj = eval('_this.$refs.RowInfo.$refs.editor_' + value)
            obj[0].setHtml(row_value)
          } else { }
        })
        imageArr.forEach(function (value, i) {
          if (row_name === value.substring(0, value.lastIndexOf('_'))) {
            window.URL = window.URL || window.webkitURL
            var xhr = new XMLHttpRequest()
            xhr.open('GET', _this.$constant.NewStaticUrl + row_value + '?access_token=' + getToken(), true)
            // 至关重要
            xhr.responseType = 'blob'
            xhr.onload = function (e) {
              if (this.status === 200) {
                // 至关重要
                let oFileReader = new FileReader()
                oFileReader.onloadend = function (e) {
                  // 此处拿到的已经是 base64的图片了
                  let base64 = e.target.result
                  // console.log('方式一》》》》》》》》》', base64)
                  _this.$refs.RowInfo.RowInfo_from[value] = base64
                  var imgFile = eval('_this.$refs.RowInfo.$refs.imgFile_' + value)
                  imgFile[0].src = base64
                  var imgFileDel = eval('_this.$refs.RowInfo.$refs.imgFileDel_' + value)
                  imgFileDel[0].style.display = 'inline-block'
                }
                oFileReader.readAsDataURL(this.response)
              }
            }
            xhr.send()
          } else { }
        })
      })
      // console.log(row_arr, this.columnsData)
      // console.log(this.$refs.RowInfo.RowInfo_from)
      this.$refs.RowInfo.RowInfo_modal = true
    },
    // 增加、修改
    ParentOperate () {
      var judge = this.$refs.RowInfo.RowInfo_judge
      var rows = []
      // 将对象属性枚举
      var Cname_arr = Object.keys(this.$refs.RowInfo.RowInfo_from)
      var _this = this
      Cname_arr.forEach(function (value, i) {
        // 截取‘_’并移除最后一个数组元素
        var n_name_arr = value.split('_').slice(0, -1)
        // 拼接元素形成新键名
        var n_name = n_name_arr.join('_')
        // 原始数据值
        var o_value = _this.$refs.RowInfo.RowInfo_from[value]
        var n_value
        var longtextArr = _this.longtextArr
        var l_index = longtextArr.findIndex(v => v === n_name)
        if (l_index < 0) {
          n_value = o_value
        } else {
          var dom = eval('_this.$refs.RowInfo.$refs.editor_' + n_name)
          n_value = dom[0].getHtml()
        }
        var obj = { 'name': n_name, 'value': n_value }
        rows.push(obj)
      })
      var tableId = this.$route.query.tableId
      var tableName = this.$route.params.name
      var id = this.$refs.RowInfo.RowInfo_Id
      var data = {
        'tableId': tableId,
        'tableName': tableName,
        'rows': rows
      }
      if (judge === 'create') {
        this.$refs.RowInfo.$refs.RowInfoList.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            createCmsRow(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功插入')
                this.$refs.RowInfo.RowInfo_modal = false
                this.searchTable(1)
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else if (judge === 'update') {
        this.$refs.RowInfo.$refs.RowInfoList.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            updateCmsRow(data).then(res => {
              this.$Spin.hide()
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.RowInfo.RowInfo_modal = false
                this.searchTable(1)
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else { }
    },
    // 删除行数据
    handleDelete (x) {
      var data = x
      this.$refs.ready.ready_title = '删除已记录的数据'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除确定删除该列?'
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      var name = this.$route.params.name
      delCmsRow(id, name).then(res => {
        this.$Spin.hide()
        this.$refs.ready.ready_modal = false
        this.$Message.success('成功删除')
        this.searchTable(1)
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    // 查找数据
    searchTable (page) {
      var rows = []
      var arr = this.SearchList.items
      arr.forEach(function (val, i) {
        var name = val.key
        var value = val.value
        if (value === null || value === undefined) {
          value = ''
        } else { }
        var obj
        if (value === '') { } else {
          obj = { 'name': name, 'value': value }
          // obj = '{\\"name"\\:\\"' + name + '"\\,\\"value"\\:\\"' + value + '"\\}'
          rows.push(obj)
        }
      })
      this.getRowList(page, rows)
    },
    // 获取行数据
    getRowList (x, z) {
      const pageNum = x
      const pageSize = 5
      const name = this.$route.params.name
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': { 'name': name, 'rows': z }
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      cmsRowList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    }
  },
  created () {
  },
  mounted () {
    this.getColumnList()
    this.searchTable(1)
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.query.tableId) {
        this.getColumnList()
        this.searchTable(1)
      }
    }
  }
}
</script>

<style scoped>
/deep/.show_second span {
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
/deep/ .ivu-table-hidden {
  visibility: collapse;
}
</style>
