<template>
  <Modal
    :title="RowInfo_title"
    v-model="RowInfo_modal"
    :closable="false"
    :mask-closable="false">
    <span>
      <Form ref="RowInfoList" :model="RowInfo_from" label-position="left" :rules="ruleValidate"  :label-width="120">
        <div>
          <!--开始循环-->
          <div v-for="(item,index) in RowInfoList.items"  :key="index" >
            <div v-if="item.DomType==='input'&&item.RowType!=='longtext'">
              <FormItem :label="item.title" :prop="item.key+ '_' +item.RowPrecision" class="formSr">
                <Input v-model="RowInfo_from[item.key+ '_' +item.RowPrecision]"></Input>
              </FormItem>
            </div>
            <div v-else-if="item.DomType==='date'">
              <FormItem :label="item.title" :prop="item.key+ '_' +item.RowPrecision">
                <DatePicker type="date" placeholder=""  :value="RowInfo_from[item.key+ '_' +item.RowPrecision]" @on-change="RowInfo_from[item.key+ '_' +item.RowPrecision]=$event" format="yyyy-MM-dd"></DatePicker>
              </FormItem>
            </div>
            <div v-else-if="item.DomType==='datetime'">
              <FormItem :label="item.title" :prop="item.key+ '_' +item.RowPrecision">
                <DatePicker type="datetime" placeholder=""  :value="RowInfo_from[item.key+ '_' +item.RowPrecision]" @on-change="RowInfo_from[item.key+ '_' +item.RowPrecision]=$event" format="yyyy-MM-dd HH:mm:ss"></DatePicker>
              </FormItem>
            </div>
            <div v-else-if="item.RowType==='longtext'">
              <FormItem :label="item.title" :prop="item.key+ '_' +item.RowPrecision" class="editorC">
                <editor :ref="'editor_'+item.key" :eId="item.key+'_'+index" :value="RowInfo_from[item.key+ '_' +item.RowPrecision]"/>
              </FormItem>
            </div>
            <div v-else-if="item.DomType==='image'">
              <FormItem :label="item.title" :prop="item.key+ '_' +item.RowPrecision">
                <div class="upload-img">
                  <img :ref="'imgFile_'+item.key+ '_' +item.RowPrecision" :src="RowInfo_from[item.key+ '_' +item.RowPrecision]" alt="">
                </div>
                <div style="display:none;" :ref="'imgFileDel_'+item.key+ '_' +item.RowPrecision" class="upload-img-close">
                  <Icon type="ios-close" size="20" @click="removeFile(item.key+ '_' +item.RowPrecision)"></Icon>
                </div>
                <label class="upload-file">
                  <input accept="image/gif,image/jpeg,image/png" type="file" @change="handlePreview(item.key+ '_' +item.RowPrecision)"/>
                  <Icon type="ios-camera" size="20"></Icon>
                </label>
              </FormItem>
            </div>
            <div v-else>
              <FormItem :label="item.title">
                <Select v-model="RowInfo_from[item.key+ '_' +item.RowPrecision]">
                  <Option v-for="(option, i) in item.RowSelectRange" :value="option.code" :key="i">{{option.value}}</Option>
                </Select>
              </FormItem>
            </div>
          </div>
        </div>
      </Form>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="RowInfo_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
</template>

<script>
import Editor from './editor'
export default {
  components: {
    Editor
  },
  data () {
    return {
      RowInfo_title: '行数据',
      RowInfo_modal: false,
      RowInfo_Validate: '',
      RowInfo_judge: '',
      RowInfo_Id: '0',
      RowInfoList: {
        items: []
      },
      RowInfo_from: {},
      ruleValidate: {
      }
    }
  },
  methods: {
    next () {
      this.$emit('operate', '')
    },
    removeFile (keyID) {
      this.RowInfo_from[keyID] = ''
      this.$refs['imgFile_' + keyID][0].src = ''
      this.$refs['imgFileDel_' + keyID][0].style.display = 'none'
    },
    handlePreview (keyID) {
      if (event.target.files[0]) {
        const self = this
        const r = new FileReader()
        r.readAsDataURL(event.target.files[0])
        r.onloadend = function (e) {
          self.RowInfo_from[keyID] = this.result
          self.$refs['imgFile_' + keyID][0].src = this.result
          self.$refs['imgFileDel_' + keyID][0].style.display = 'inline-block'
        }
      }
    }
  },
  created () {
    // console.log(this.RowInfo_from)
  }
}
</script>

<style lang="less" scoped>
  /deep/ .ivu-date-picker{
    width: 100%;
  }
  /deep/ .ivu-modal{
    width: 70% !important;
    top: 10px !important;
  }
  /deep/.ivu-form-item-content,.ivu-select{
    width: 230px;
  }
  .editorC /deep/.ivu-form-item-content{
    width: auto!important;
  }
  .upload-img{
    display: inline-block;
    width: 60px;
    height: 60px;
    text-align: center;
    line-height: 60px;
    border: 1px solid transparent;
    border-radius: 4px;
    overflow: hidden;
    background: #fff;
    position: relative;
    box-shadow: 0 1px 1px rgba(0,0,0,.2);
    margin-right: 4px;
  }
  .upload-img img{
    width: 100%;
    height: 100%;
  }
  .upload-file input {
    position: absolute;
    opacity: 0;
    margin: 0px;
    border: 0px;
    width: 58px;
    height: 58px;
    cursor: pointer;
  }
  .upload-file i{
    font-size: 20px;
    cursor: pointer;
    margin: 20px 20px;
    position: absolute;
  }
  .upload-img-close {
    overflow: hidden;
    display: inline-block;
    width: 58px;
    height:58px;
    line-height: 58px;
    text-align: center;
    cursor: pointer;
  }
</style>
