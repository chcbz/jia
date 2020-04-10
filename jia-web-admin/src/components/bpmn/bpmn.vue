<template>
  <div id="content_bpmn">
    <div id="wrap"
         class="wrap">
      <div id="canvas"
           class="canvas"></div>
      <div id="js-properties-panel"
           class="panel"></div>
    </div>
    <div id="operate_bpmn"
         class="operate">
      <a ref="saveDiagram"
         id="saveDiagram"
         style="display: none"></a>
      <Button @click="BpmnDown">下载当前工作流</Button>
      <Button type="primary"
              @click="BpmnClick">保存当前工作流</Button>
      <Upload action="//jsonplaceholder.typicode.com/posts/"
              accept="application/zip"
              :before-upload="uploadFile">
        <Button type="warning">上传Zip</Button>
      </Upload>
      <div v-if="fileName !== ''">{{fileName}}</div>
    </div>
  </div>
</template>
<script>
import Modeler from 'bpmn-js/lib/Modeler'
import propertiesPanelModule from 'bpmn-js-properties-panel'
import propertiesProviderModule from 'bpmn-js-properties-panel/lib/provider/camunda'
import camundaModdleDescriptor from 'camunda-bpmn-moddle/resources/camunda'
export default {
  name: 'Bpmn',
  data () {
    return {
      // 初始内容
      bpmnXmlStr: '<?xml version="1.0" encoding="UTF-8"?>\n' +
        '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" xsi:schemaLocation="http://www.omg.org/spec/BPMN/20100524/MODEL BPMN20.xsd" id="sample-diagram" targetNamespace="http://bpmn.io/schema/bpmn">\n' +
        '  <process id="Process_1" isExecutable="false">\n' +
        // '    <startEvent id="_BPMNShape_StartEvent_2"/>\n' +
        '  </process>\n' +
        '  <bpmndi:BPMNDiagram id="BPMNDiagram_1">\n' +
        '    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">\n' +
        // '      <bpmndi:BPMNShape id="_BPMNShape_StartEvent_2" bpmnElement="StartEvent_1">\n' +
        // '        <dc:Bounds height="36.0" width="36.0" x="412.0" y="240.0"/>\n' +
        // '      </bpmndi:BPMNShape>\n' +
        '    </bpmndi:BPMNPlane>\n' +
        '  </bpmndi:BPMNDiagram>\n' +
        '</definitions>',
      // bpmn建模器
      bpmnModeler: null,
      xmlStr: null,
      encodedData: null,
      // 文件上传成功的名字
      fileName: ''
    }
  },
  created: function () {

  },
  methods: {
    // 下载文件
    BpmnDown () {
      let saveDiagram = document.getElementById('saveDiagram')
      saveDiagram.click()
      var saveDiagramClass = saveDiagram.getAttribute('class')
      if (saveDiagramClass) { } else {
        this.$Message.error('请先编辑流程图！')
      }
    },
    // 上传文件
    uploadFile (file) {
      this.$emit('fileUpload', file)
      return false
    },
    // 创建画板
    createNewDiagram () {
      // the diagram you are going to display
      // this.bpmnXmlStr = '<?xml version="1.0" encoding="UTF-8"?>\n' +
      //   '<bpmn2:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:bpmn2="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xsi:schemaLocation="http://www.omg.org/spec/BPMN/20100524/MODEL BPMN20.xsd" id="sample-diagram" targetNamespace="http://bpmn.io/schema/bpmn">\n' +
      //   '  <bpmn2:process id="Process_1" isExecutable="false">\n' +
      //   '  </bpmn2:process>\n' +
      //   '  <bpmndi:BPMNDiagram id="BPMNDiagram_1">\n' +
      //   '    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">\n' +
      //   '      <bpmndi:BPMNShape id="_BPMNShape_StartEvent_2" bpmnElement="StartEvent_1">\n' +
      //   '        <dc:Bounds height="36.0" width="36.0" x="412.0" y="240.0"/>\n' +
      //   '      </bpmndi:BPMNShape>\n' +
      //   '    </bpmndi:BPMNPlane>\n' +
      //   '  </bpmndi:BPMNDiagram>\n' +
      //   '</bpmn2:definitions>'
      // import diagram
      this.bpmnModeler.importXML(this.bpmnXmlStr, function (err) {
        if (err) {
          // import failed :-(
        } else {
          // we did well!

        }
      })
    },
    // 点击按钮触发上传当前工作流
    BpmnClick () {
      this.$emit('BpmnSave', this.encodedData)
    },
    // 保存bpmn
    saveDiagram (done) {
      // 把传入的done再传给bpmn原型的saveXML函数调用
      this.bpmnModeler.saveXML({ format: true }, function (err, xml) {
        done(err, xml)
      })
    },
    // 当图发生改变的时候会调用这个函数，这个data就是图的xml
    setEncoded (link, name, data) {
      // 把xml转换为URI，下载要用到的
      this.encodedData = 'data:application/bpmn20-xml;charset=UTF-8,' + encodeURIComponent(data)
      // 获取到图的xml，保存就是把这个xml提交给后台
      this.xmlStr = data
      // 下载图的具体操作,改变a的属性，className令a标签可点击，href令能下载，download是下载的文件的名字
      if (data) {
        link.className = 'active'
        link.href = this.encodedData
        link.download = name
      }
    }
  },
  mounted () {
    // create a modeler
    this.bpmnModeler = new Modeler(
      {
        container: '#canvas',
        // 添加控制板
        propertiesPanel: {
          parent: '#js-properties-panel'
        },
        additionalModules: [
          // 左边工具栏以及节点
          propertiesProviderModule,
          // 右边的工具栏
          propertiesPanelModule
        ],
        moddleExtensions: {
          camunda: camundaModdleDescriptor
        }
      })
    // 下载画图
    let _this = this
    // 获取a标签dom节点
    const downloadLink = this.$refs.saveDiagram
    // 给图绑定事件，当图有发生改变就会触发这个事件
    this.bpmnModeler.on('commandStack.changed', function () {
      _this.saveDiagram(function (err, xml) {
        _this.setEncoded(downloadLink, 'diagram.bpmn', err ? null : xml)
      })
    })
    this.createNewDiagram(this.bpmnModeler)
  }
}
</script>

<style scoped>
/*左边工具栏以及编辑节点的样式*/
@import '../../../node_modules/bpmn-js/dist/assets/diagram-js.css';
@import '../../../node_modules/bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
@import '../../../node_modules/bpmn-js/dist/assets/bpmn-font/css/bpmn-codes.css';
@import '../../../node_modules/bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css';
/*右边工具栏样式*/
@import '../../../node_modules/bpmn-js-properties-panel/dist/assets/bpmn-js-properties-panel.css';
/deep/ .bjs-powered-by {
  display: none;
}
.canvas {
  background: #fff;
  overflow: hidden;
  height: 100%;
}
#content_bpmn {
  height: 100%;
}
.wrap {
  height: calc(100% - 50px);
  position: relative;
}
.operate {
  margin: 10px;
  height: 48px;
}
.operate button {
  margin: 5px;
}
.operate .ivu-upload {
  display: inline;
}
.panel {
  position: absolute;
  right: 0;
  top: 0;
  width: 300px;
  height: 100%;
}
/deep/ .bpp-properties-panel {
  overflow: auto;
  height: 100%;
  border: 1px solid #dedede;
  background-color: #f8f8f8;
  position: relative;
}
</style>
