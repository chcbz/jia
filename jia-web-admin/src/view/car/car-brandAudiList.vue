<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="制造商">
                <Select v-model="searchList.brandMf" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in brandMfArr" :value="option.id" :key="v">{{option.value}}</Option>
                </Select>
              </FormItem>
            </div>
          </div>
          <div  class="search_chil_div">
            <div>
              <FormItem label="系列">
                <Input v-model="searchList.carSeries"></Input>
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
        <Button style="margin: 10px 0;" type="success" @click="handleCreate">新建车辆系列</Button>
      </div>
      <tables ref="tables"   border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <brandAudi-template ref="brandAudiTemplate" @operate="ParentOperate"></brandAudi-template>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import brandAudiTemplate from './modal/brandAudi-template'
import { carBrandAudiList, carBrandAudiCreate, carBrandAudiUpdate, carBrandAudiDel } from '@/api/data'

export default {
  components: {
    Tables,
    brandAudiTemplate,
    Ready
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        carSeries: '',
        brandMf: '',
        abbr: '',
        initials: ''
      },
      brandMfArr: [],
      columns: [
        { title: '系列',
          key: 'carSeries'
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
      this.$constant.appendBrandMf(this, 'brandMfArr')
    },
    // 创建
    handleCreate () {
      this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.resetFields()
      this.$refs.brandAudiTemplate.brandAudiTemplate_title = '新增车辆系列'
      this.$refs.brandAudiTemplate.brandAudiTemplate_modal = true
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.carSeries = ''
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.brandMf = ''
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.abbr = ''
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.initials = ''
      this.$refs.brandAudiTemplate.brandAudiTemplate_judge = 'create'
    },
    // 修改
    handleUpdate (x) {
      this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.resetFields()
      this.$refs.brandAudiTemplate.brandAudiTemplate_title = '修改车辆系列'
      this.$refs.brandAudiTemplate.brandAudiTemplate_modal = true
      this.$refs.brandAudiTemplate.brandAudiTemplate_id = x.id
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.brandMf = x.brandMf
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.carSeries = x.carSeries
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.abbr = x.abbr
      this.$refs.brandAudiTemplate.brandAudiTemplate_form.initials = x.initials
      this.$refs.brandAudiTemplate.brandAudiTemplate_judge = 'update'
    },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.brandAudiTemplate.brandAudiTemplate_judge
      var id = this.$refs.brandAudiTemplate.brandAudiTemplate_id
      var carSeries = this.$refs.brandAudiTemplate.brandAudiTemplate_form.carSeries
      var brandMf = this.$refs.brandAudiTemplate.brandAudiTemplate_form.brandMf
      var abbr = this.$refs.brandAudiTemplate.brandAudiTemplate_form.abbr
      var initials = this.$refs.brandAudiTemplate.brandAudiTemplate_form.initials
      var data = {
        'carSeries': carSeries,
        'brandMf': brandMf,
        'abbr': abbr,
        'initials': initials
      }
      if (judge === 'create') {
        this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.validate((valid) => {
          if (valid) {
            carBrandAudiCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.brandAudiTemplate.brandAudiTemplate_modal = false
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
        this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.validate((valid) => {
          if (valid) {
            data['id'] = id
            carBrandAudiUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.brandAudiTemplate.brandAudiTemplate_modal = false
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
      var name = data.carSeries
      this.$refs.ready.ready_title = '删除车辆系列'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      var id = this.$refs.ready.ready_params.id
      carBrandAudiDel(id).then(res => {
        window.location.reload()
      })
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getcarBrandAudiList(page, JSON.stringify(obj))
    },
    getcarBrandAudiList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      carBrandAudiList(data).then(res => {
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
