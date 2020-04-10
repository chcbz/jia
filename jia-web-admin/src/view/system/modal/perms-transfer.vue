<template>
  <Modal
    :title="PermsTransfer_title"
    v-model="PermsTransfer_modal"
    :closable="false"
    :mask-closable="false">
    <Transfer
      :data="PermsInitData"
      :target-keys="PermsKeys"
      :render-format="Permsrender"
      :titles="PermsTransfer_titles"
      filterable
      @on-change="handlePermsChange">
    </Transfer>
    <div slot="footer">
      <Button type="text" size="large" @click="PermsTransfer_modal=false">取消</Button>
    </div>
  </Modal>
</template>

<script>
import { getPermsList } from '@/api/data'
export default {
  data () {
    return {
      PermsTransfer_title: '',
      PermsTransfer_titles: ['可选权限', '已选权限'],
      PermsTransfer_modal: false,
      PermsInitData: [],
      PermsKeys: [],
      PermsTransfer_templateId: ''
    }
  },
  methods: {
    getPerms (level) {
      const pageNum = 1
      var data = {
        'pageNum': pageNum,
        'search': '{"status":"' + 1 + '","level":"' + level + '"}'
      }
      getPermsList(data).then(res => {
        var _this = this
        this.PermsInitData = []
        var result = res.data.data
        result.forEach((val, i) => {
          var id = val.id
          var module = val.module
          var func = val.func
          var label = module + '-' + func
          var obj = { 'key': i, 'id': id, 'label': label }
          _this.PermsInitData.push(obj)
        })
      })
    },
    Permsrender (item) {
      return item.label
    },
    // 元素移动,父组件保存方法
    handlePermsChange (newKeys, direction, moveKeys) {
      this.PermsKeys = newKeys
      this.$emit('PermsChange', '')
    }
  },
  created () {
  }
}
</script>

<style lang="less" scoped>
  /deep/.ivu-modal-body{
    margin: 0 5%;
  }
</style>
