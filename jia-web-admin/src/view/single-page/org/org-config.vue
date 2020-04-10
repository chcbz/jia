<template>
  <div>
    <Card>
      <Row>
        <Col offset="1"  :sm="14" :md="14" :lg="8">
          <Form ref="org_form" :model="org_form" label-position="left" :rules="ruleValidate"  :label-width="120">
            <FormItem label="组织logo">
              <Upload
                :before-upload="UploadLogo"
                action="//jsonplaceholder.typicode.com/posts/"
                :format="['jpg','jpeg','png']"
              >
                <Button icon="ios-cloud-upload-outline">logo上传</Button>
              </Upload>
              <div v-if="showLogo !== ''" class="logo_wrap">
                <img :src="showLogo"/>
              </div>
              <span v-show="!showLogo" style="color: red">没有上传记录</span>
            </FormItem>
            <FormItem label="组织小logo">
              <Upload
                :before-upload="UploadLogoIcon"
                action="//jsonplaceholder.typicode.com/posts/"
                :format="['jpg','jpeg','png']"
              >
                <Button icon="ios-cloud-upload-outline">小logo上传</Button>
              </Upload>
              <div v-if="showLogoIcon !== ''" class="logoIcon_wrap">
                <img :src="showLogoIcon"/>
              </div>
              <span v-show="!showLogoIcon" style="color: red">没有上传记录</span>
            </FormItem>
            <FormItem label="组织代码" prop="code">
              <Input disabled v-model="org_form.code"></Input>
            </FormItem>
            <FormItem label="组织名称" prop="name">
              <Input v-model="org_form.name"></Input>
            </FormItem>
            <FormItem>
              <Button type="primary"  @click="updateConfig">保存</Button>
            </FormItem>
          </Form>
        </Col>
      </Row>
    </Card>
  </div>
</template>
<script>
import { getOrgInfo, updateOrg, updateOrgLogo } from '@/api/data'

export default {
  components: {
  },
  data () {
    return {
      org_form: {
        id: 0,
        code: '',
        name: '',
        logo: null,
        logoIcon: null
      },
      showLogo: '',
      showLogoIcon: '',
      ruleValidate: {
      }
    }
  },
  // computed: {
  //   orgDate () {
  //     return this.$store.state.user.orgDate
  //   }
  // },
  // watch: {
  //   orgDate (val) {
  //     console.log(val)
  //     this.initOrg(val)
  //   }
  // },
  methods: {
    initVform () {
      var v_arr = [
        { 'name': 'name', 'method': 'NotNull' }
      ]
      var strArr = JSON.stringify(v_arr)
      this.ruleValidate = this.$constant.CreateVerify(strArr)
      this.initOrg()
    },
    initOrg () {
      var id = this.$store.state.user.position
      getOrgInfo(id).then(res => {
        this.$refs.org_form.resetFields()
        var data = res.data.data
        if (data.logo) {
          this.showLogo = this.$constant.StaticUrl + data.logo
        } else {}
        if (data.logoIcon) {
          this.showLogoIcon = this.$constant.StaticUrl + data.logoIcon
        } else {}
        this.org_form.id = data.id
        this.org_form.code = data.code
        this.org_form.name = data.name
      })
    },
    // 上传logo
    UploadLogo (file) {
      this.org_form.logo = file
      var reads = new FileReader()
      reads.readAsDataURL(file)
      var _this = this
      reads.onload = function (e) {
        _this.showLogo = this.result
      }
      return false
    },
    UploadLogoIcon (file) {
      this.org_form.logoIcon = file
      var reads = new FileReader()
      reads.readAsDataURL(file)
      var _this = this
      reads.onload = function (e) {
        _this.showLogoIcon = this.result
      }
      return false
    },
    updateConfig () {
      var id = this.org_form.id
      var name = this.org_form.name
      var logo = this.org_form.logo
      var logoIcon = this.org_form.logoIcon
      var data = {
        'id': id,
        'name': name
      }
      this.$refs.org_form.validate((valid) => {
        if (valid) {
          this.$Spin.show()
          updateOrg(data).then(res => {
            this.$Spin.hide()
            // 基本信息修改成功
            if (res.data.msg === 'ok') {
              if (logo) {
                updateOrgLogo(id, 1, logo).then(res => {
                  if (res.data.msg === 'ok') {
                    this.$Message.success('成功上传长头像')
                  } else { this.$Message.error(res.data.msg) }
                }).catch(ess => {
                  this.$Message.error('请联系管理员')
                })
              } else {}
              if (logoIcon) {
                updateOrgLogo(id, 2, logoIcon).then(res => {
                  if (res.data.msg === 'ok') {
                    this.$Message.success('成功上传方形头像')
                  } else { this.$Message.error(res.data.msg) }
                }).catch(ess => {
                  this.$Message.error('请联系管理员')
                })
              } else {}
              this.$Message.success('成功上传修改')
            } else {
              this.$Message.error(res.data.msg)
            }
          }).catch(ess => {
            this.$Spin.hide()
            this.$Message.error('请联系管理员')
          })
        } else {
          return false
        }
      })
    }
  },
  mounted () {
    this.initVform()
  }
}
</script>

<style scoped>
  .logo_wrap img{
    width: 180px;
    height: auto;
  }
  .logoIcon_wrap img{
    width: auto;
    height: 60px;
  }
</style>
