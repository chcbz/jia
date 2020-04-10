<template>
  <div>
    <Card>
      <Form :model="searchList"
            ref="searchList"
            inline
            :label-width="80"
            class="search_form">
        <div class="search_div">
          <div class="search_chil_div">
            <div>
              <FormItem label="文件名称">
                <Input v-model="searchList.name"></Input>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
      <div class="search_operate">
        <Button @click="searchTable(1)">查询</Button>
        <Button style="margin: 10px 10px;"
                type="success"
                @click="handleCreate">新增文件</Button>
      </div>
      <tables ref="tables"
              editable
              border
              v-model="tableData"
              :columns="columns"
              @ChangPage="ParentChange" />
    </Card>
    <ready ref="ready"
           @Next="ParentNext"></ready>
    <file-info ref="FileInfo"
                @operate="ParentOperate"></file-info>
  </div>
</template>
<script>
import Clipboard from 'clipboard'
import Tables from '_c/tables'
import Ready from '_c/modal/ready'
import FileInfo from './modal/file-info'
import { FileList, uploadCmsFile, delFile } from '@/api/data'

export default {
  components: {
    Tables,
    Ready,
    FileInfo
  },
  data () {
    return {
      pageNum: 1,
      pageSize: 5,
      // 搜索条件集合
      searchList: {
        name: ''
      },
      columns: [
        {
          title: '文件名称',
          key: 'name',
          render: (h, params) => {
            var data = params.row
            var uri = data.uri
            return h(
              'span', {
                style: {
                  color: '#00b5ff',
                  cursor: 'pointer'
                },
                on: {
                  click: () => {
                    window.open(this.$constant.StaticUrl + uri)
                  }
                }
              }, data.name)
          }
        },
        {
          title: '文件大小',
          key: 'size'
        },
        {
          title: '文件后缀',
          key: 'extension'
        },
        {
          title: '创建时间',
          key: 'createTime',
          render: (h, params) => {
            var data = params.row
            var createTime = data.createTime
            var result = this.$constant.formatDateTime(createTime)
            return h('span', result)
          }
        },
        {
          title: '操作',
          key: 'operate',
          width: 160,
          fixed: 'right',
          render: (h, params) => {
            var data = params.row
            return h('div', {
              class: {
                table_operate: true
              }
            }, [
              h('Button', {
                class: 'copyBtn',
                props: {
                  size: 'small'
                },
                attrs: {
                  'data-clipboard-action': 'copy',
                  btn: this.$constant.StaticUrl + data.uri
                }
              }, '复制路径'),
              // h('Button', {
              //   props: {
              //     type: 'info',
              //     size: 'small'
              //   },
              //   on: {
              //     click: () => {
              //       this.handleUpdate(data)
              //     }
              //   }
              // }, '修改'),
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
    // 创建表格
    handleCreate () {
      this.$refs.FileInfo.FileInfo_modal = true
      this.$refs.FileInfo.FileInfo_title = '添加文件'
      this.$refs.FileInfo.FileInfo_judge = 'create'
      this.$refs.FileInfo.FileInfo_form.pic = null
    },
    // 修改表格
    // handleUpdate (x) {
    //   this.$refs.FileInfo.$refs.FileInfo_form.resetFields()
    //   this.$refs.FileInfo.FileInfo_title = '修改表格'
    //   this.$refs.FileInfo.FileInfo_modal = true
    //   this.$refs.FileInfo.FileInfo_id = x.id
    //   this.$refs.FileInfo.FileInfo_form.token = x.token
    //   this.$refs.FileInfo.FileInfo_form.encodingaeskey = x.encodingaeskey
    //   this.$refs.FileInfo.FileInfo_form.level = (x.level).toString()
    //   this.$refs.FileInfo.FileInfo_form.name = x.name
    //   this.$refs.FileInfo.FileInfo_form.account = x.account
    //   this.$refs.FileInfo.FileInfo_form.original = x.original
    //   this.$refs.FileInfo.FileInfo_form.signature = x.signature
    //   this.$refs.FileInfo.FileInfo_form.country = x.country
    //   this.$refs.FileInfo.FileInfo_form.province = x.province
    //   this.$refs.FileInfo.FileInfo_judge = 'update'
    // },
    // 创建、修改
    ParentOperate () {
      var judge = this.$refs.FileInfo.FileInfo_judge
      var file = this.$refs.FileInfo.FileInfo_form.pic
      var data = {
        'file': file
      }
      if (judge === 'create') {
        this.$Spin.show()
        uploadCmsFile(data).then(res => {
          if (res.data.msg === 'ok') {
            this.$Spin.hide()
            this.$Message.success('成功新增文件')
            this.$refs.FileInfo.FileInfo_modal = false
            this.searchTable(1)
          } else {
            this.$Spin.hide()
            this.$Message.error(res.data.msg)
          }
        }).catch(ess => {
          this.$Spin.hide()
          this.$Message.error('请联系管理员')
        })
      } else { }
    },
    // 删除文件
    handleDelete (x) {
      var data = x
      var name = data.name
      this.$refs.ready.ready_title = '删除文件'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_content = '是否删除-' + name
      this.$refs.ready.ready_params = data
    },
    ParentNext () {
      this.$Spin.show()
      var id = this.$refs.ready.ready_params.id
      delFile(id).then(res => {
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
      var s_obj = this.searchList
      var obj = this.$constant.appendSearch(s_obj)
      obj.type = 4
      obj.clientId = this.$store.state.user.orgDate.clientId
      this.getCmsFileList(page, JSON.stringify(obj))
    },
    getCmsFileList (x, z) {
      const pageNum = x
      const pageSize = this.pageSize
      var data = {
        'pageNum': x,
        'pageSize': pageSize,
        'search': z
      }
      this.$refs.tables.insidePageNum = pageNum
      this.$refs.tables.insidePageSize = pageSize
      FileList(data).then(res => {
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
    this.clipboard = new Clipboard('.copyBtn', {
      text: function (trigger) {
        return trigger.getAttribute('btn')
      }
    })
    this.clipboard.on('success', e => {
      this.$Message.success('已复制到剪切板！')
      e.clearSelection()
    })
    this.clipboard.on('error', e => {
      this.$Message.error('复制失败')
    })
  },
  destroyed () {
    this.clipboard.destroy() // 页面销毁时清除复制
  }
}
</script>
