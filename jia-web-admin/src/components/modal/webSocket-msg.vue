<template>
  <Modal
    :title="webSocketMsg_title"
    v-model="webSocketMsg_modal"
    :closable="false"
    :mask-closable="false">
      <ul class="web_wrap"  style="overflow: auto">
        <ul ref="web_wrap">

        </ul>
        <div ref="msg_end" style="height:0px; overflow:hidden"></div>
        <!--<li v-for="(item,index) in webSocketMsg_arr"  :key="index">-->
        <!--{{item}}-->
        <!--</li>-->
      </ul>
    <div slot="footer">
      <Button type="error" size="large" @click="closeWs" :disabled="webSocketMsg_disabled">{{webSocketMsg_status}}</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      webSocketMsg_title: '服务器安装信息',
      webSocketMsg_modal: false,
      webSocketMsg_disabled: true,
      webSocketMsg_status: '安装中'
      // webSocketMsg_arr: []
    }
  },
  methods: {
    closeWs () {
      this.$emit('wsClose', '')
    }
  },
  watch: {
    // webSocketMsg_arr () {
    //   this.$nextTick(() => {
    //     var wrap = this.$refs.web_wrap
    //     wrap.scrollTop = wrap.scrollHeight
    //   })
    // }
  },
  created () {

  }
}
</script>

<style lang="less" scoped>
  /deep/.ivu-modal{
    width: 70% !important;
    height: 80%;
  }
/deep/.ivu-modal-content{
   width: 100%;
   height: 100%;
 }
/deep/.ivu-modal-header{
  height: 10%;
}
/deep/.ivu-modal-body{
  height: 78%;
}
/deep/.ivu-modal-footer{
  height: 12%;
}
  .web_wrap{
    overflow: auto;
    height: 99%;
    background-color: #eeeeee;
  }
</style>
