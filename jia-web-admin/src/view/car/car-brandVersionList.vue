<template>
  <div>
    <Card>
      <Form :model="searchList" ref="searchList"  inline :label-width="80" class="search_form">
        <div  class="search_div">
          <div  class="search_chil_div">
            <div>
              <FormItem label="系列">
                <Select v-model="searchList.audi" class="search_select" clearable filterable>
                  <Option v-for="(option, v) in audiArr" :value="option.id" :key="v">{{option.value}}</Option>
                </Select>
              </FormItem>
            </div>
          </div>
          <div  class="search_chil_div">
            <div>
              <FormItem label="车型规格">
                <Input v-model="searchList.version"></Input>
              </FormItem>
            </div>
          </div>
          <div  class="search_chil_div">
            <div>
              <FormItem label="车型名称">
                <Input v-model="searchList.vName"></Input>
              </FormItem>
            </div>
          </div>
          </div>
      </Form>
      <div class="search_operate">
        <Button  @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 0;" type="success" @click="handleCreate">新建车辆型号</Button>
      </div>
      <tables ref="tables"   border v-model="tableData" :columns="columns"  @ChangPage=" ParentChange"/>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <brandVersion-template ref="brandVersionTemplate" @operate="ParentOperate"></brandVersion-template>
  </div>
</template>
<script>
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import brandVersionTemplate from './modal/brandVersion-template'
import { carBrandVersionList, carBrandVersionCreate, carBrandVersionUpdate, carBrandVersionDel } from '@/api/data'

export default {
  components: {
    Tables,
    brandVersionTemplate,
    Ready
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        audi: '',
        version: '',
        vName: ''
      },
      audiArr: [],
      columns: [
        { title: '车型规格',
          key: 'version'
        },
        { title: '车型名称',
          key: 'vName'
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
      this.$constant.appendBrandAudi(this, 'audiArr')
    },
    // 创建
    handleCreate () {
      this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.resetFields()
      this.$refs.brandVersionTemplate.brandVersionTemplate_title = '创建车辆型号'
      this.$refs.brandVersionTemplate.brandVersionTemplate_modal = true
      this.$refs.brandVersionTemplate.brandVersionTemplate_form.audi = ''
      this.$refs.brandVersionTemplate.brandVersionTemplate_form.version = ''
      this.$refs.brandVersionTemplate.brandVersionTemplate_form.vName = ''
      this.$refs.brandVersionTemplate.brandVersionTemplate_judge = 'create'
    },
    // 修改
    handleUpdate (x) {
      this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.resetFields()
      this.$refs.brandVersionTemplate.brandVersionTemplate_title = '修改车辆型号'
      this.$refs.brandVersionTemplate.brandVersionTemplate_modal = true
      this.$refs.brandVersionTemplate.brandVersionTemplate_id = x.id
      this.$refs.brandVersionTemplate.brandVersionTemplate_form.audi = x.audi
      this.$refs.brandVersionTemplate.brandVersionTemplate_form.version = x.version
      this.$refs.brandVersionTemplate.brandVersionTemplate_form.vName = x.vName
      this.$refs.brandVersionTemplate.brandVersionTemplate_judge = 'update'
    },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.brandVersionTemplate.brandVersionTemplate_judge
      var id = this.$refs.brandVersionTemplate.brandVersionTemplate_id
      var audi = this.$refs.brandVersionTemplate.brandVersionTemplate_form.audi
      var version = this.$refs.brandVersionTemplate.brandVersionTemplate_form.version
      var vName = this.$refs.brandVersionTemplate.brandVersionTemplate_form.vName
      var data = {
        'audi': audi,
        'version': version,
        'vName': vName
      }
      if (judge === 'create') {
        this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.validate((valid) => {
          if (valid) {
            carBrandVersionCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功创建')
                this.$refs.brandVersionTemplate.brandVersionTemplate_modal = false
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
        this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.validate((valid) => {
          if (valid) {
            data['id'] = id
            carBrandVersionUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Message.success('成功修改')
                this.$refs.brandVersionTemplate.brandVersionTemplate_modal = false
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
      var name = data.vName
      this.$refs.ready.ready_title = '删除车辆型号'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      var id = this.$refs.ready.ready_params.id
      carBrandVersionDel(id).then(res => {
        window.location.reload()
      })
    },
    // 查找数据
    searchTable (page) {
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      this.getcarBrandVersionList(page, JSON.stringify(obj))
    },
    getcarBrandVersionList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      var data = {
        'pageNum': pageNum,
        'pageSize': pageSize,
        'search': z
      }
      carBrandVersionList(data).then(res => {
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
