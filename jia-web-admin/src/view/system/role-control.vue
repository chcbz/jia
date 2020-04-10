<template>
  <div>
    <i-switch v-model="role_switch" @on-change="change" />
    <span>权限控制</span>
  </div>
</template>
<script>
export default {
  name: 'role-control',
  data () {
    return {
      role_switch: false,
      access: []
    }
  },
  created: function () {
    this.init()
  },
  methods: {
    init () {
      this.access = this.$store.state.user.access
      var index = this.access.indexOf(1)
      if (index > -1) {
        this.role_switch = true
      } else { this.role_switch = false }
    },
    change (status) {
      var index = this.access.indexOf(110)
      if (index > -1) {
        this.access.splice(index, 1)
      } else { this.access.push(110) }
      this.$store.state.user.access = this.access
      // this.$Message.info('开关状态：' + status)
    }
  }
}
</script>
