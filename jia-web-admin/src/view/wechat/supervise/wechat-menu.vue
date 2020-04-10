<template>
  <div style="padding: 20px">
  <Row  :gutter="12">
    <Col  :md="11" :lg="7" offset="4">
      <Card>
        <Tree
          :data="MenuData"
          ref="MenuTree"
          :render="renderContent"
        >
        </Tree>
      </Card>
    </Col>
    <Col  :md="13" :lg="10">
      <Card>
        <p slot="title" class="title">编辑-{{updateName}}</p>
          <!-- 编辑表单 -->
        <Form :model="formMenu" ref="formMenu"  label-position="left" :label-width="200">
          <FormItem label="菜单标题 (必填)" :rules="{validator: NotNull, trigger: 'blur'}" prop="title">
            <Input v-model="formMenu.title"></Input>
          </FormItem>
          <FormItem label="响应动作类型" :rules="{validator: TypeChance, trigger: 'blur'}" prop="type">
            <RadioGroup v-model="formMenu.type">
              <Radio   label="view">网页</Radio>
              <Radio  label="click">点击</Radio>
              <Radio  label="miniprogram">小程序</Radio>
              <Radio  label="null">无</Radio>
            </RadioGroup>
          </FormItem>
          <FormItem label="KEY值" :rules="{validator: clickV, trigger: 'blur'}" prop="key">
            <Input v-model="formMenu.key"></Input>
          </FormItem>
          <FormItem label="网页链接" :rules="{validator: viewV, trigger: 'blur'}" prop="url">
            <Input v-model="formMenu.url"></Input>
          </FormItem>
          <FormItem label="新增永久素材接口返回的media_id">
            <Input v-model="formMenu.media_id"></Input>
          </FormItem>
          <FormItem label="小程序的appid" :rules="{validator: miniprogramV, trigger: 'blur'}" prop="appid">
            <Input v-model="formMenu.appid"></Input>
          </FormItem>
          <FormItem label="小程序的页面路径" :rules="{validator: miniprogramV, trigger: 'blur'}" prop="pagepath">
            <Input v-model="formMenu.pagepath"></Input>
          </FormItem>
        </Form>
        <Button id="post" type="primary" @click="saveMenu">保存</Button>
      </Card>
    </Col>
  </Row>
    <ready ref="ready" @Next="ParentNext"></ready>
  </div>
</template>
<script>
import { WxMpMenu, WxMpMenuCreate } from '@/api/data'
import Ready from '_c/modal/ready'

export default {
  components: {
    Ready
  },
  data () {
    return {
      formMenu: {
        title: '',
        type: 'null',
        appId: '',
        key: '',
        mediaId: '',
        pagePath: '',
        url: ''
      },
      updateName: '',
      i: 1,
      del_root: [],
      del_node: null,
      del_data: null,
      MenuData: [
        {
          title: '',
          expand: true,
          render: (h, { root, node, data }) => h('span', {
            style: {
              display: 'inline-block',
              width: '100%'
            }
          }, [
            h('span', [
              h('span', {
                attrs: {
                  class: 'ivu-tree-title '

                },
                on: {
                  click: () => { this.handleselect(data) }
                }

              }
                , data.title)
            ]),
            h('span', {
              style: {
                display: 'inline-block',
                float: 'right',
                marginRight: '32px'
              }
            }, [
              h('Button', {
                props: Object.assign({}, this.buttonProps, {
                  icon: 'ios-add',
                  type: 'primary'
                }),
                style: {
                  width: '52px'
                },
                on: {
                  click: () => { this.append(data) }
                }
              })
            ])
          ]),
          children: []
        }
      ],
      buttonProps: {
        size: 'small'
      }
    }
  },
  methods: {
    NotNull (rule, y, callback) {
      if (y === '') {
        return callback(new Error('该项为必填项'))
      } else {
        callback()
      }
    },
    // 类型判断
    TypeChance (rule, y, callback) {
      if (y === '') {
        return callback(new Error('该项为必填项'))
      } else {
        var nodeKey_arr = []
        var treeKey_arr = this.MenuData['0'].children
        treeKey_arr.forEach((val, i) => {
          var nodeKey = val.nodeKey
          nodeKey_arr.push(nodeKey)
        })
        var dq_nodeKey = this.formMenu.nodeKey
        var nodeKey_judge = nodeKey_arr.indexOf(dq_nodeKey)
        if (nodeKey_judge < 0 && y === 'null') {
          return callback(new Error('当前编辑的菜单并非为主菜单,必须选择类型!'))
        } else { callback() }
      }
    },
    // click判断
    clickV (rule, y, callback) {
      var type = this.formMenu.type
      if (!y && type === 'click') {
        return callback(new Error('click点击类型下该项必须'))
      } else {
        callback()
      }
    },
    // view判断
    viewV (rule, y, callback) {
      var type = this.formMenu.type
      if (!y && type === 'view') {
        return callback(new Error('view点击类型下该项必须'))
      } else {
        callback()
      }
    },
    // miniprogram判断
    miniprogramV (rule, y, callback) {
      var type = this.formMenu.type
      if (!y && type === 'miniprogram') {
        return callback(new Error('miniprogram点击类型下该项必须'))
      } else {
        callback()
      }
    },
    // 为每个子节点进行渲染
    renderContent (h, { root, node, data }) {
      return h('span', {
        style: {
          display: 'inline-block',
          width: '100%'
        }
      }, [
        h('span', [
          h('span',
            {
              attrs: {
                class: 'ivu-tree-title'

              },
              on: {
                click: () => { this.handleselect(data) }
              }
            },
            data.title)

        ]),
        h('span', {
          style: {
            display: 'inline-block',
            float: 'right',
            marginRight: '32px'
          }
        }, [
          h('Button', {
            props: Object.assign({}, this.buttonProps, {
              icon: 'ios-add'
            }),
            style: {
              marginRight: '8px'
            },
            on: {
              click: () => { this.append(data) }
            }
          }),
          h('Button', {
            props: Object.assign({}, this.buttonProps, {
              icon: 'ios-remove'
            }),
            on: {
              click: () => { this.remove(root, node, data) }
            }
          })
        ])
      ])
    },
    // 树状结构初始化
    initTreeData (x) {
      var res_data = x
      var tree_arr = this.MenuData[0]
      // 改变开头父节点名称
      var first_title = this.$route.query.name
      tree_arr.title = first_title
      // 定义后台获取的子节点
      if (x === undefined) {} else {
        var menu = res_data.menu.buttons
        tree_arr.children = this.getTree(menu)
      }
      // console.log(this.MenuData)
    },
    // 数据解析
    getTree (tree = []) {
      let arr = []
      var _this = this
      if (!!tree && tree.length !== 0) {
        tree.forEach(item => {
          let obj = {}
          obj.title = item.name
          obj.appId = item.appId
          obj.key = item.key
          obj.mediaId = item.mediaId
          obj.pagePath = item.pagePath
          obj.type = (item.type === null) ? 'null' : item.type
          obj.url = item.url
          obj.expand = true
          obj.children = _this.getTree(item.subButtons) // 递归调用
          arr.push(obj)
        })
      }
      return arr
    },
    // 数据恢复
    postTree (tree = []) {
      let arr = []
      var _this = this
      if (!!tree && tree.length !== 0) {
        tree.forEach(item => {
          let obj = {}
          obj.name = item.title
          obj.appId = item.appId
          obj.key = item.key
          obj.mediaId = item.mediaId
          obj.pagePath = item.pagePath
          obj.type = (item.type === 'null') ? null : item.type
          obj.url = item.url
          obj.subButtons = _this.postTree(item.children) // 递归调用
          arr.push(obj)
        })
      }
      return arr
    },
    // 点击树节点时传值
    handleselect (data) {
      var nodeKey = data.nodeKey
      if (nodeKey === 0) {
        this.$Message.error('该项不能编辑')
      } else {
        // this.$refs.formMenu.resetFields()
        this.formMenu = data
        this.updateName = data.title
      }
    },
    // 增加子节点
    append (data) {
      this.$refs.ready.ready_title = '添加新菜单'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_judge = 'add'
      this.$refs.ready.ready_params = data
      this.$refs.ready.ready_content = '确定在[' + data.title + ']下添加新菜单吗?'
    },
    // 删除子节点
    remove (root, node, data) {
      this.$refs.ready.ready_title = '删除菜单'
      this.$refs.ready.ready_modal = true
      this.$refs.ready.ready_judge = 'del'
      this.$refs.ready.ready_content = '确定删除[' + data.title + ']菜单吗?'
      this.del_root = root
      this.del_node = node
      this.del_data = data
    },
    // 添加、删除节点
    ParentNext () {
      const judge = this.$refs.ready.ready_judge
      const appid = this.$route.params.appid
      var buttons
      if (judge === 'add') {
        var tree_data = this.$refs.ready.ready_params
        const children = tree_data.children || []
        children.push({
          title: '未定义' + this.i,
          appId: null,
          key: null,
          mediaId: null,
          pagePath: null,
          type: 'view',
          url: 'http://wx.wydiy.com/',
          children: [],
          expand: true
        })
        this.$set(tree_data, 'children', children)
        this.i = this.i + 1
        buttons = this.postTree(this.MenuData)['0'].subButtons
        WxMpMenuCreate(appid, buttons).then(res => {
          if (res.data.msg === 'ok') {
            this.$Message.success('成功保存')
          } else {
            this.$Message.error(res.data.msg)
          }
        }).catch(ess => {
          this.$Message.error('请联系管理员')
        })
      } else {
        var root = this.del_root
        var node = this.del_node
        var data = this.del_data
        const parentKey = root.find(el => el === node).parent
        const parent = root.find(el => el.nodeKey === parentKey).node
        const index = parent.children.indexOf(data)
        parent.children.splice(index, 1)
        buttons = this.postTree(this.MenuData)['0'].subButtons
        WxMpMenuCreate(appid, buttons).then(res => {
          if (res.data.msg === 'ok') {
            this.$Message.success('删除成功')
          } else {
            this.$Message.error(res.data.msg)
          }
        }).catch(ess => {
          this.$Message.error('请联系管理员')
        })
      }
    },
    // 更新节点
    saveMenu () {
      var appid = this.$route.params.appid
      var buttons = this.postTree(this.MenuData)['0'].subButtons
      var obj_length = Object.keys(this.formMenu).length
      if (obj_length === 7) {
        this.$Message.error('请选择编辑对象!')
      } else {
        this.$refs.formMenu.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            WxMpMenuCreate(appid, buttons).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功保存')
              } else {
                this.$Spin.hide()
                this.$Message.error(res.data.msg)
              }
            }).catch(ess => {
              this.$Spin.hide()
              this.$Message.error('请联系管理员')
            })
          } else {
            return false
          }
        })
      }
    },
    getWxMpMenu () {
      const appid = this.$route.params.appid
      WxMpMenu(appid).then(res => {
        var data = res.data.data
        this.initTreeData(data)
      })
    }
  },
  mounted () {
    this.getWxMpMenu()
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.appid) {
        this.getWxMpMenu()
      }
    }
  }
}
</script>

<style scoped>
  /*/deep/.formMenu input,/deep/.formMenu textarea{*/
    /*width: 80%;*/
  /*}*/
</style>
