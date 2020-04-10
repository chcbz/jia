<template>
  <div class="user-avator-dropdown">
    <Dropdown @on-click="handleClick">
      <Badge :dot="!!messageUnreadCount" style="margin-right: 10px">
        <Avatar :src="userAvator"/>
      </Badge>
      <span>{{userName}}</span>
      <Icon :size="18" type="md-arrow-dropdown"></Icon>
      <DropdownMenu slot="list">
        <DropdownItem name="message">
          消息中心<Badge style="margin-left: 10px" :count="messageUnreadCount"></Badge>
        </DropdownItem>
        <DropdownItem name="updateuser">修改个人信息</DropdownItem>
        <DropdownItem name="updateorg">修改组织信息</DropdownItem>
        <DropdownItem v-show="$constant.findRole('action_update')" name="superadmin">总后台管理</DropdownItem>
        <DropdownItem name="logout">退出登录</DropdownItem>
      </DropdownMenu>
    </Dropdown>
  </div>
</template>

<script>
import './user.less'
import { mapActions } from 'vuex'
export default {
  name: 'User',
  props: {
    userAvator: {
      type: String,
      default: ''
    },
    userName: {
      type: String,
      default: ''
    },
    messageUnreadCount: {
      type: Number,
      default: 0
    }
  },
  methods: {
    ...mapActions([
      'handleLogOut'
    ]),
    logout () {
      this.handleLogOut().then(() => {
        this.$router.push({
          name: 'login'
        })
      })
    },
    message () {
      this.$router.push({
        name: 'message_page'
      })
    },
    updateuser () {
      this.$router.push({
        name: 'update_user'
      })
    },
    updateorg () {
      this.$router.push({
        name: 'update_org'
      })
    },
    superadmin () {
      this.$router.push({
        name: 'action_system'
      })
    },
    handleClick (name) {
      switch (name) {
        case 'logout': this.logout()
          break
        case 'message': this.message()
          break
        case 'updateuser': this.updateuser()
          break
        case 'updateorg': this.updateorg()
          break
        case 'superadmin': this.superadmin()
          break
      }
    }
  }
}
</script>
