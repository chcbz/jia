<template>
  <Modal :title="bpmn_resource_title"
         v-model="bpmn_resource_modal"
         :closable="false"
         :mask-closable="false">
    <div id="wrap"
         class="wrap">
      <div id="canvas"
           class="canvas"></div>
    </div>
    <div slot="footer">
      <Button type="primary"
              size="large"
              @click="bpmn_resource_modal=false">关闭</Button>
    </div>
  </Modal>
</template>

<script>
import Modeler from 'bpmn-js/lib/Modeler'
export default {
  data () {
    return {
      bpmn_resource_title: '工作流部署',
      bpmn_resource_modal: false,
      // bpmn建模器
      bpmnModeler: null,
      bpmnXmlStr: null
    }
  },
  methods: {
    // 创建画板
    createNewDiagram () {
      // import diagram
      this.bpmnModeler.importXML(this.bpmnXmlStr, function (err) {
        if (err) {
          // import failed :-(
        } else {
          // we did well!

        }
      })
    }
  },
  mounted () {
    // create a modeler
    this.bpmnModeler = new Modeler({ container: '#canvas' })
  },
  watch: {
    bpmnXmlStr: {
      handler (newName, oldName) {
        this.createNewDiagram(this.bpmnModeler)
      }
    }
  }
}
</script>

<style scoped>
/*左边工具栏以及编辑节点的样式*/
@import "../../../../node_modules/bpmn-js/dist/assets/diagram-js.css";
@import "../../../../node_modules/bpmn-js/dist/assets/bpmn-font/css/bpmn.css";
@import "../../../../node_modules/bpmn-js/dist/assets/bpmn-font/css/bpmn-codes.css";
@import "../../../../node_modules/bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css";
/deep/ .ivu-modal {
  width: calc(100% - 100px) !important;
  top: 50px;
}
/deep/ .bjs-powered-by {
  display: none;
}
/deep/ .djs-palette {
  display: none;
}
.canvas {
  background: #fff;
  overflow: hidden;
  height: 100%;
}
.wrap {
  border: 1px solid #e0e0e0;
  height: 500px;
}
/deep/ .bpp-properties-panel {
  overflow: auto;
  height: 701px;
  border: 1px solid #dedede;
  background-color: #f8f8f8;
  position: relative;
}
</style>
