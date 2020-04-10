<template>
  <Modal
    :title="selectRange_title"
    v-model="selectRange_modal"
    :closable="false"
    :mask-closable="false">
    <span>
      <Card>
        <Form  :model="selectRangeList" ref="selectRangeList"  label-position="left"  inline  :label-width="120">
        <div v-for="(item,index) in selectRangeList.items"  :key="index" class="range_div">
        <FormItem label="code" :rules="{validator: NotNull, trigger: 'blur'}"  :prop="'items.' + index + '.code'">
            <Input v-model="item.code"></Input>
        </FormItem>
           <FormItem label="value" :rules="{validator: NotNull, trigger: 'blur'}"  :prop="'items.' + index + '.value'">
            <Input v-model="item.value"></Input>
        </FormItem>
            <div  class="s_icon_div">
               <li><Icon type="ios-remove-circle" size="30" @click="removeRform(index)"/></li>
             </div>
          </div>
        </Form>
         <div  class="s_icon_div"><Icon type="ios-add-circle" size="30" @click="addRform"/></div>
      </Card>
    </span>
    <div slot="footer">
      <Button type="text" size="large" @click="selectRange_modal=false">取消</Button>
      <Button type="primary" size="large" @click="next">保存</Button>
    </div>
  </Modal>
</template>

<script>
export default {
  data () {
    return {
      index: 0,
      selectRange_title: '可选范围设置',
      selectRange_modal: false,
      selectRange_index: '',
      selectRangeList: {
        items: [{
          index: 0,
          code: '',
          value: ''
        }]
      }
    }
  },
  methods: {
    next () {
      this.$emit('nextSucess', '')
    },
    NotNull (rule, y, callback) {
      if (y === '') {
        return callback(new Error('该项为必填项'))
      } else {
        callback()
      }
    },
    addRform () {
      this.index++
      this.selectRangeList.items.push(
        {
          index: this.index,
          code: '',
          value: ''
        }
      )
    },
    removeRform (i) {
      if (i === 0) {
        this.$Message.error('该列不能删除')
      } else {
        this.selectRangeList.items.splice(i, 1)
      }
    }
  },
  created () {
  }
}
</script>

<style lang="less" scoped>
  .range_div{
    display: flex;
  }
/deep/ .s_icon_div{
  margin-top: 0;
}
</style>
