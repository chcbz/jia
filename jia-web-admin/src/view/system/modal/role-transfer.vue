<template>
  <Modal
    :title="RoleTransfer_title"
    v-model="RoleTransfer_modal"
    :closable="false"
    :mask-closable="false">
      <Transfer
        :data="RoleInitData"
        :target-keys="RoleKeys"
        :render-format="Rolerender"
        :titles="RoleTransfer_titles"
        filterable
        @on-change="handleRoleChange">
      </Transfer>
    <div slot="footer">
      <Button type="text" size="large" @click="RoleTransfer_modal=false">关闭</Button>
      <!--<Button type="primary" size="large" @click="handleRole">确定</Button>-->
    </div>
  </Modal>
</template>

<script>
import { getRoleList } from '@/api/data'
export default {
  data () {
    return {
      RoleTransfer_title: '',
      RoleTransfer_titles: ['可选角色', '已选角色'],
      RoleTransfer_modal: false,
      RoleInitData: [],
      RoleKeys: [],
      RoleTransfer_templateId: ''
    }
  },
  methods: {
    getRole (level) {
      const pageNum = 1
      var data = {
        'pageNum': pageNum,
        'search': '{"level":"' + level + '"}'
      }
      getRoleList(data).then(res => {
        var _this = this
        this.RoleInitData = []
        var result = res.data.data
        result.forEach((val, i) => {
          var id = val.id
          var label = val.name
          var obj = { 'key': i, 'id': id, 'label': label }
          _this.RoleInitData.push(obj)
        })
      })
    },
    Rolerender (item) {
      return item.label
    },
    // 元素移动,父组件保存方法
    handleRoleChange (newKeys, direction, moveKeys) {
      this.RoleKeys = newKeys
      this.$emit('RoleChange', '')
    }
  },
  created () {}
}
</script>

<style lang="less" scoped>
  /deep/.ivu-modal-body{
    margin: 0 5%;
  }
</style>
