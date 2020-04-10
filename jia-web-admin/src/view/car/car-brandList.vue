<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="品牌">
                <Input v-model="searchList.brand"></Input>
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
        <Button style="margin: 10px 0;" type="success" @click="handleCreate">新建车辆品牌</Button>
      </div>
      <tables ref="tables"   border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <brand-template ref="brandTemplate" @operate="ParentOperate"></brand-template>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import brandTemplate from './modal/brand-template'
import { carBrandList, carBrandCreate, carBrandUpdate, carBrandDel } from '@/api/data'

export default {
  components: {
    Tables,
    brandTemplate,
    Ready
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        brand: '',
        abbr: '',
        initials: ''
      },
      columns: [
        { title: '品牌名称',
          key: 'brand'
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
    // 创建
    handleCreate () {
      this.$refs.brandTemplate.$refs.brandTemplate_form.resetFields()
      this.$refs.brandTemplate.brandTemplate_title = '创建车辆品牌'
      this.$refs.brandTemplate.brandTemplate_modal = true
      this.$refs.brandTemplate.brandTemplate_form.brand = ''
      this.$refs.brandTemplate.brandTemplate_form.abbr = ''
      this.$refs.brandTemplate.brandTemplate_form.initials = ''
      this.$refs.brandTemplate.brandTemplate_judge = 'create'
    },
    // 修改
    handleUpdate (x) {
      this.$refs.brandTemplate.$refs.brandTemplate_form.resetFields()
      this.$refs.brandTemplate.brandTemplate_title = '修改车辆品牌'
      this.$refs.brandTemplate.brandTemplate_modal = true
      this.$refs.brandTemplate.brandTemplate_id = x.id
      this.$refs.brandTemplate.brandTemplate_form.brand = x.brand
      this.$refs.brandTemplate.brandTemplate_form.abbr = x.abbr
      this.$refs.brandTemplate.brandTemplate_form.initials = x.initials
      this.$refs.brandTemplate.brandTemplate_judge = 'update'
    },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.brandTemplate.brandTemplate_judge
      var id = this.$refs.brandTemplate.brandTemplate_id
      var brand = this.$refs.brandTemplate.brandTemplate_form.brand
      var abbr = this.$refs.brandTemplate.brandTemplate_form.abbr
      var initials = this.$refs.brandTemplate.brandTemplate_form.initials
      var data = {
        'brand': brand,
        'abbr': abbr,
        'initials': initials
      }
      if (judge === 'create') {
        this.$refs.brandTemplate.$refs.brandTemplate_form.validate((valid) => {
          if (valid) {
            carBrandCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.brandTemplate.brandTemplate_modal = false
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
        this.$refs.brandTemplate.$refs.brandTemplate_form.validate((valid) => {
          if (valid) {
            data['id'] = id
            carBrandUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.brandTemplate.brandTemplate_modal = false
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
      var name = data.brand
      this.$refs.ready.ready_title = '删除车辆品牌'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      var id = this.$refs.ready.ready_params.id
      carBrandDel(id).then(res => {
        window.location.reload()
      })
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getcarBrandList(page, JSON.stringify(obj))
    },
    getcarBrandList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      carBrandList(data).then(res => {
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
    this.searchTable(1)
  }
}
</script>
