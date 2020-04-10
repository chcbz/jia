<template>
  <div>
    <editor ref="editor" :eId="eId" :value="content" @on-change="handleChange"/>
    <Button @click="changeContent">修改编辑器内容</Button>
  </div>
</template>

<script>
import Editor from '_c/editor'
export default {
  name: 'editor_page',
  components: {
    Editor
  },
  data () {
    return {
      eId: '0',
      content: '测试'
    }
  },
  methods: {
    handleChange (html, text) {
      console.log(html, text)
    },
    changeContent () {
      console.log(this.$refs.editor.getHtml())
    }
  }
}
</script>

<style>

</style>
