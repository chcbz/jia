<template>
  <div class="user-avator-dropdown">
    <Dropdown @on-click="handleClick">
      <span>{{orgName}}</span>
        <Icon :size="18" type="md-arrow-dropdown" v-show="orgShow"></Icon>
        <DropdownMenu slot="list" v-show="orgShow">
          <DropdownItem v-for="(item,index) in orgArr"  :key="index" :name="item.id">{{item.name}}</DropdownItem>
        </DropdownMenu>
    </Dropdown>
  </div>
</template>

<script>
import { getUserOrg, changeUserPosition } from '@/api/data'
import { localSave } from '@/libs/util'
export default {
  name: 'Org',
  props: {
  },
  data () {
    return {
      orgName: '',
      orgArr: [],
      orgShow: true
    }
  },
  methods: {
    handleClick (name) {
      var position = name
      changeUserPosition(position).then(res => {
        this.$Spin.hide()
        if (res.data.msg === 'ok') {
          this.$Message.success('成功修改')
          var tagNavList = this.$store.state.app.tagNavList
          tagNavList.splice(0, tagNavList.length)
          localSave('tagNaveList', '')
          this.$router.push({ name: 'home' })
          window.location.reload()
        } else { this.$Message.error(res.data.msg) }
      }).catch(ess => {
        this.$Spin.hide()
        this.$Message.error('请联系管理员')
      })
    },
    getOrgInfo () {
      var id = this.$store.state.user.userId
      getUserOrg(id).then(res => {
        var arr = res.data.data
        this.orgArr = arr
        var position = this.$store.state.user.position
        var _this = this
        arr.forEach(function (val, i) {
          var orgId = val.id
          var orgName = val.name
          if (orgId === position) {
            _this.orgName = orgName
          } else {}
        })
        var length = arr.length
        this.orgShow = (length > 1)
      })
    }
  },
  mounted () {
    this.getOrgInfo()
  }
}
</script>
