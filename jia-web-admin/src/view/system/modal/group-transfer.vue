<template>
  <Modal
    :title="GroupTransfer_title"
    v-model="GroupTransfer_modal"
    :closable="false"
    :mask-closable="false">
      <Transfer
        :data="GroupInitData"
        :target-keys="GroupKeys"
        :render-format="Grouprender"
        :titles="GroupTransfer_titles"
        filterable
        @on-change="handleGroupChange">
      </Transfer>
    <div slot="footer">
      <Button type="text" size="large" @click="GroupTransfer_modal=false">取消</Button>
    </div>
  </Modal>
</template>

<script>
import { getGroupList } from '@/api/data'
export default {
  data () {
    return {
      GroupTransfer_title: '',
      GroupTransfer_titles: ['可选用户组', '已选用户组'],
      GroupTransfer_modal: false,
      GroupInitData: [],
      GroupKeys: [],
      GroupTransfer_templateId: ''
    }
  },
  methods: {
    getGroup () {
      const pageNum = 1
      var data = {
        'pageNum': pageNum
      }
      getGroupList(data).then(res => {
        var _this = this
        this.GroupInitData = []
        var result = res.data.data
        result.forEach((val, i) => {
          var id = val.id
          var label = val.name
          var obj = { 'key': i, 'id': id, 'label': label }
          _this.GroupInitData.push(obj)
        })
      })
    },
    Grouprender (item) {
      return item.label
    },
    // 元素移动,父组件保存方法
    handleGroupChange (newKeys, direction, moveKeys) {
      this.GroupKeys = newKeys
      this.$emit('GroupChange', '')
    }
  },
  created () {
    this.getGroup()
  }
}
</script>

<style lang="less" scoped>
  /deep/.ivu-modal-body{
    margin: 0 5%;
  }
</style>
