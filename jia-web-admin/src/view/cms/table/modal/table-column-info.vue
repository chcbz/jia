<template>
  <div>
  <Modal
    :title="TableColumnInfo_title"
    v-model="TableColumnInfo_modal"
    :closable="false"
    :mask-closable="false">
    <div>
      <Form ref="colFormList" :model="colFormList" label-position="left"   :label-width="200">
          <card v-for="(item,index) in colFormList.items"  :key="index" class="colform">
         <FormItem label="列名 (必填)" :rules="{validator: NotNull, trigger: 'blur'}"  :prop="'items.' + index + '.name'">
            <Input v-model="item.name"></Input>
        </FormItem>
        <FormItem label="列类型">
          <Select v-model="item.type">
            <Option value="int" label="整数">
            </Option>
            <Option value="double" label="小数">
            </Option>
            <Option value="varchar" label="文本">
            </Option>
            <Option value="date" label="日期">
            </Option>
            <Option value="datetime" label="日期时间">
            </Option>
            <Option value="longtext" label="富文本">
            </Option>
            <Option value="image" label="图片">
            </Option>
          </Select>
        </FormItem>
            <FormItem label="默认值">
              <Input v-model="item.defaultValue"></Input>
            </FormItem>
            <FormItem label="长度" v-show="'int,varchar,double'.indexOf(item.type) >= 0" :rules="{validator: newNotInt,  trigger: 'blur'}" :item-index="index" :prop="'items.' + index + '.precision'">
              <Input v-model="item.precision"></Input>
            </FormItem>
        <FormItem label="精度" v-show="item.type=='double'"  :rules="{validator: NotInt,  trigger: 'blur'}"  :prop="'items.' + index + '.scale'">
            <Input v-model="item.scale" :maxlength="1"></Input>
        </FormItem>
          <FormItem label="是否可为空">
            <RadioGroup v-model="item.notnull">
                <Radio :label=1>非空</Radio>
                <Radio :label=0>可空</Radio>
            </RadioGroup>
        </FormItem>
        <FormItem label="是否在列表显示">
          <RadioGroup v-model="item.isList">
            <Radio :label=1>是</Radio>
            <Radio :label=0>否</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="是否作为搜索条件">
          <RadioGroup v-model="item.isSearch">
            <Radio :label=1>是</Radio>
            <Radio :label=0>否</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem label="列中文名 (必填)" :rules="{validator: NotNull, trigger: 'blur'}"  :prop="'items.' + index + '.remark'">
            <Input v-model="item.remark"></Input>
        </FormItem>
             <div  class="s_icon_div">
               <li><Button type="info" @click="setRange(index)">设置可选范围</Button></li>
             </div>
            </card>
      </Form>
    </div>
    <div slot="footer">
      <Button type="text" size="large" @click="TableColumnInfo_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">确定</Button>
    </div>
  </Modal>
  <select-range ref="SelectRange"  @nextSucess="saveRange"></select-range>
  </div>
</template>

<script>
import SelectRange from './select-range'
export default {
  components: {
    SelectRange
  },
  data () {
    return {
      index: 0,
      TableColumnInfo_title: '表格',
      TableColumnInfo_modal: false,
      TableColumnInfo_Validate: '',
      TableColumnInfo_judge: '',
      TableColumnInfo_id: '',
      colFormList: {
        items: [{
          index: 0,
          name: '',
          type: 'int',
          precision: '',
          scale: '',
          notnull: 0,
          isSearch: 0,
          isList: 1,
          selectRange: null,
          defaultValue: '',
          remark: ''
        }]
      },
      ruleValidate: {}
      // cruleValidate: {}
    }
  },
  methods: {
    // 设置可选项
    setRange (x) {
      this.$refs.SelectRange.selectRange_index = x
      var arr = [{ index: 0, code: '', value: '' }]
      var range
      if (this.colFormList.items[x].selectRange === null) {
        range = arr
      } else {
        var Sarr = JSON.parse(this.colFormList.items[x].selectRange)
        Sarr.forEach(function (val, i) {
          Sarr[i].index = i
        })
        range = Sarr
      }
      this.$refs.SelectRange.selectRangeList.items = range
      // this.$refs.SelectRange.$refs.selectRangeList.resetFields()
      this.$refs.SelectRange.selectRange_modal = true
    },
    // 保存可选项
    saveRange () {
      this.$refs.SelectRange.$refs.selectRangeList.validate((valid) => {
        if (valid) {
          var arr = this.$refs.SelectRange.selectRangeList.items
          arr.forEach(function (val, i) {
            delete arr[i].index
          })
          var i = this.$refs.SelectRange.selectRange_index
          this.colFormList.items[i].selectRange = JSON.stringify(arr)
          this.$Message.success('成功保存')
          this.$refs.SelectRange.selectRange_modal = false
        } else {
          return false
        }
      })
    },
    NotNull (rule, y, callback) {
      if (y === '') {
        return callback(new Error('该项为必填项'))
      } else {
        callback()
      }
    },
    newNotInt (rule, y, callback, source) {
      var key
      var i
      for (key in source) {
        i = parseInt(key.split('.')[1])
      }
      var type = this.colFormList.items[i].type
      var num = y
      if (type === 'int' || type === 'varchar') {
        if (isNaN(num) === true) {
          return callback(new Error('请填写数字'))
        } else if (y === '') {
          return callback(new Error('当列类型为“int”、“varchar”必填'))
        } else {
          callback()
        }
      } else {
        if (isNaN(num) === true) {
          return callback(new Error('请填写数字'))
        } else {
          callback()
        }
      }
    },
    NotInt (rule, y, callback) {
      var num = y
      if (isNaN(num) === true) {
        return callback(new Error('请填写数字'))
      } else {
        callback()
      }
    },
    next () {
      this.$emit('operate', '')
    },
    initVform () {
      var v_arr = [
        { 'name': 'name', 'method': 'NotNull' },
        { 'name': 'remark', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      // var cv_arr = [
      //   { 'name': 'items.0.name', 'method': 'NotNull' }
      // ]
      // var cstrArr = JSON.stringify(cv_arr)
      // this.cruleValidate = this.$constant.CreateVerify(cstrArr)
    }
  },
  created () {
    this.initVform()
  }
}
</script>

<style scoped>
  /deep/ .ivu-modal{
    width: 780px!important;
  }
  .colform{
    margin-top: 5px;
  }
  /deep/.ivu-modal {
    top: 10px;
  }
</style>
