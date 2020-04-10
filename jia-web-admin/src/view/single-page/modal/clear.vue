<template>
  <Modal
    :title="clear_title"
    v-model="clear_modal"
    :closable="false"
    :mask-closable="false">
    <span>{{typeV}}</span>
    <div slot="footer">
      <Button type="text" size="large" @click="clear_modal=false">取消</Button>
      <Button type="primary" size="large" @click="urlGo">确定</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  props: {
    typeN: {
      type: String,
      default: ''
    },
    typeV: {
      type: String,
      default: ''
    }
  },
  data () {
    return {
      clear_title: '确认框',
      clear_modal: false
    }
  },
  methods: {
    urlGo () {
      var type = this.typeN
      if (type === 'sms') {
        this.$router.push({ name: 'sms_config' })
      } else if (type === 'cms') {
        this.$router.push({ name: 'cms_config' })
      } else {}
    }
  },
  created () {
  }
}
</script>

<style lang="less">

</style>
