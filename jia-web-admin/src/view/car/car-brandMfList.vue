<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="品牌">
                <Select v-model="searchList.brand" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in brandArr" :value="option.id" :key="v">{{option.value}}</Option>
                </Select>
              </FormItem>
            </div>
          </div>
          <div  class="search_chil_div">
            <div>
              <FormItem label="制造商">
                <Input v-model="searchList.brandMf"></Input>
              </FormItem>
            </div>
          </div>
          <div  class="search_chil_div">
            <div>
              <FormItem label="缩写">
                <Input v-model="searchList.abbr"></Input>
              </FormItem>
            </div>
          </div>
          <div  class="search_chil_div">
            <div>
              <FormItem label="首字母">
                <Input v-model="searchList.initials"></Input>
              </FormItem>
            </div>
          </div>
          </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 0;" type="success" @click="handleCreate">新建车辆制造商</Button>
      </div>
      <tables ref="tables"   border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <brandMf-template ref="brandMfTemplate" @operate="ParentOperate"></brandMf-template>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import brandMfTemplate from './modal/brandMf-template'
import { carBrandMfList, carBrandMfCreate, carBrandMfUpdate, carBrandMfDel } from '@/api/data'

export default {
  components: {
    Tables,
    brandMfTemplate,
    Ready
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        brand: '',
        brandMf: '',
        abbr: '',
        initials: ''
      },
      brandArr: [],
      columns: [
        { title: '制造商',
          key: 'brandMf'
        },
        { title: '缩写',
          key: 'abbr'
        },
        { title: '首字母',
          key: 'initials'
        },
        {
          title: '操作',
          key: 'operate',
          width: 140,
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            return h('div', {
              class: {
                table_operate: true
              }
            }, [
              // h('Button', {
              //   props: {
              //     size: 'small'
              //   },
              //   on: {
              //     click: () => {
              //       var msgid = data.msgid
              //       this.$router.push({ path: 'sms_reply_list/' + msgid })
              //     }
              //   }
              // }, '详细'),
              h('Button', {
                props: {
                  type: 'info',
                  size: 'small'
                },
                on: {
                  click: () => {
                    this.handleUpdate(data)
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
                    this.handleDelete(data)
                  }
                }
              }, '删除')
            ])
          }
        }
      ],
      tableData: []
    }
  },
  methods: {
    // 初始化页面
    initVue () {
      this.searchTable(1)
      this.$constant.appendBrand(this, 'brandArr')
    },
    // 创建
    handleCreate () {
      this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.resetFields()
      this.$refs.brandMfTemplate.brandMfTemplate_title = '创建车辆制造商'
      this.$refs.brandMfTemplate.brandMfTemplate_modal = true
      this.$refs.brandMfTemplate.brandMfTemplate_form.brand = ''
      this.$refs.brandMfTemplate.brandMfTemplate_form.brandMf = ''
      this.$refs.brandMfTemplate.brandMfTemplate_form.abbr = ''
      this.$refs.brandMfTemplate.brandMfTemplate_form.initials = ''
      this.$refs.brandMfTemplate.brandMfTemplate_judge = 'create'
    },
    // 修改
    handleUpdate (x) {
      this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.resetFields()
      this.$refs.brandMfTemplate.brandMfTemplate_title = '修改车辆制造商'
      this.$refs.brandMfTemplate.brandMfTemplate_modal = true
      this.$refs.brandMfTemplate.brandMfTemplate_id = x.id
      this.$refs.brandMfTemplate.brandMfTemplate_form.brandMf = x.brandMf
      this.$refs.brandMfTemplate.brandMfTemplate_form.brand = x.brand
      this.$refs.brandMfTemplate.brandMfTemplate_form.abbr = x.abbr
      this.$refs.brandMfTemplate.brandMfTemplate_form.initials = x.initials
      this.$refs.brandMfTemplate.brandMfTemplate_judge = 'update'
    },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.brandMfTemplate.brandMfTemplate_judge
      var id = this.$refs.brandMfTemplate.brandMfTemplate_id
      var brand = this.$refs.brandMfTemplate.brandMfTemplate_form.brand
      var brandMf = this.$refs.brandMfTemplate.brandMfTemplate_form.brandMf
      var abbr = this.$refs.brandMfTemplate.brandMfTemplate_form.abbr
      var initials = this.$refs.brandMfTemplate.brandMfTemplate_form.initials
      var data = {
        'brand': brand,
        'brandMf': brandMf,
        'abbr': abbr,
        'initials': initials
      }
      if (judge === 'create') {
        this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.validate((valid) => {
          if (valid) {
            carBrandMfCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.brandMfTemplate.brandMfTemplate_modal = false
                window.location.reload()
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else if (judge === 'update') {
        this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.validate((valid) => {
          if (valid) {
            data['id'] = id
            carBrandMfUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.brandMfTemplate.brandMfTemplate_modal = false
                window.location.reload()
              } else { this.$Message.error(res.data.msg) }
            }).catch(ess => {
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      } else {}
    },
    // 删除
    handleDelete (x) {
      var data = x
      var name = data.brandMf
      this.$refs.ready.ready_title = '删除车辆制造商'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      var id = this.$refs.ready.ready_params.id
      carBrandMfDel(id).then(res => {
        window.location.reload()
      })
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getcarBrandMfList(page, JSON.stringify(obj))
    },
    getcarBrandMfList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      carBrandMfList(data).then(res => {
        this.tableData = res.data.data
        this.$refs.tables.insidePageCount = res.data.total
      })
    },
    // 分页
    ParentChange (page) {
      this.searchTable(page)
    }
  },
  mounted () {
    this.initVue()
  }
}
</script>
