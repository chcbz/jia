<template>
  <Modal
    :title="ready_title"
    v-model="ready_modal"
    @on-ok="next"
    :closable="false"
    :mask-closable="false">
    <span>{{ready_content}}</span>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      ready_title: '删除用户',
      ready_modal: false,
      ready_content: '',
      ready_params: [],
      ready_judge: ''
    }
  },
  methods: {
    next () {
      this.$emit('Next', '')
    }
  }
}
</script>

<style lang="less">

</style>
