<template>
  <div>
    <Card>
      <!--内容显示-->
      <div class="linkage_content_div">
        <Row type="flex"  align="middle" class="code-row-bg">
          <!--选择容器-->
          <Col span="5">
            <div class="linkage_div">
              <Card>
                <p slot="title">
                 品牌
                </p>
                <div class="linkage_content">
                  <Input search  placeholder="搜索" v-model="searchBrand"/>
                  <ul class="linkage_content_ul">
                    <li v-for="(option, v) in NewBrandItems" :class="{choice:option.id==choiceBrand}" :ref="'brand_'+option.id" @click="brandChoice(option.id)" :key="v">{{option.value}}</li>
                  </ul>
                  <div class="linkage_operate">
                    <Button type="success" @click="brandCreate">添加</Button>
                    <Button type="primary" :disabled="!BrandClientId==true" @click="brandUpdate">修改</Button>
                    <Button type="error" :disabled="!BrandClientId==true" @click="brandDel">删除</Button>
                  </div>
                </div>
              </Card>
            </div>
          </Col>
          <!--装饰箭头-->
          <Col span="1">
            <div class="linkage_icon">
              <Icon type="md-arrow-forward" />
            </div>
          </Col>
          <Col span="5">
            <div class="linkage_div">
              <Card>
                <p slot="title">
                  制作商
                </p>
                <div class="linkage_content">
                  <Input search  placeholder="搜索" v-model="searchBrandMf"/>
                  <ul class="linkage_content_ul">
                    <li v-for="(option, v) in NewBrandMfItems" :class="{choice:option.id==choiceBrandMf}" :ref="'brandMf_'+option.id" @click="brandMfChoice(option.id)" :key="v">{{option.value}}</li>
                  </ul>
                  <div class="linkage_operate">
                    <Button type="success" @click="brandMfCreate">添加</Button>
                    <Button type="primary" :disabled="!BrandMfClientId==true" @click="brandMfUpdate">修改</Button>
                    <Button type="error" :disabled="!BrandMfClientId==true" @click="brandMfDel">删除</Button>
                  </div>
                </div>
              </Card>
            </div>
          </Col>
          <Col span="1">
            <div class="linkage_icon">
              <Icon type="md-arrow-forward" />
            </div>
          </Col>
          <Col span="5">
            <div class="linkage_div">
              <Card>
                <p slot="title">
                  系列
                </p>
                <div class="linkage_content">
                  <Input search  placeholder="搜索" v-model="searchBrandAudi"/>
                  <ul class="linkage_content_ul">
                    <li v-for="(option, v) in NewBrandAudiItems" :class="{choice:option.id==choiceBrandAudi}" :ref="'brandAudi_'+option.id" @click="brandAudiChoice(option.id)" :key="v">{{option.value}}</li>
                  </ul>
                  <div class="linkage_operate">
                    <Button type="success" @click="brandAudiCreate">添加</Button>
                    <Button type="primary" :disabled="!BrandAudiClientId==true" @click="brandAudiUpdate">修改</Button>
                    <Button type="error" :disabled="!BrandAudiClientId==true" @click="brandAudiDel">删除</Button>
                  </div>
                </div>
              </Card>
            </div>
          </Col>
          <Col span="1">
            <div class="linkage_icon">
              <Icon type="md-arrow-forward" />
            </div>
          </Col>
          <Col span="5">
            <div class="linkage_div">
              <Card>
                <p slot="title">
                  型号
                </p>
                <div class="linkage_content">
                  <Input search  placeholder="搜索" v-model="searchBrandVersion"/>
                  <ul class="linkage_content_ul">
                    <li v-for="(option, v) in NewBrandVersionItems" :class="{choice:option.id==choiceBrandVersion}" :ref="'brandVersion_'+option.id" @click="brandVersionChoice(option.id)" :key="v">{{option.value}}</li>
                  </ul>
                  <div class="linkage_operate">
                    <Button type="success" @click="brandVersionCreate">添加</Button>
                    <Button type="primary" :disabled="!BrandVersionClientId==true" @click="brandVersionUpdate">修改</Button>
                    <Button type="error" :disabled="!BrandVersionClientId==true" @click="brandVersionDel">删除</Button>
                  </div>
                </div>
              </Card>
            </div>
          </Col>
        </Row>
      </div>
      <!--内容显示end-->
      <div class="message_div">
        <Row type="flex">
          <Col span="6">
            <Card>
              <p>基本信息</p>
              <ul v-if="messageArr.length==0">
                <li>无</li>
              </ul>
              <ul v-else>
                <li v-for="(item,i) in messageArr" :key="i">{{item.key}}:{{item.value}}</li>
              </ul>
            </Card>
          </Col>
        </Row>
      </div>
    </Card>
    <ready ref="ready" @Next="ParentNext"></ready>
    <brand-template ref="brandTemplate" @operate="brandOperate"></brand-template>
    <brandMf-template ref="brandMfTemplate" @operate="brandMfOperate"></brandMf-template>
    <brandAudi-template ref="brandAudiTemplate" @operate="brandAudiOperate"></brandAudi-template>
    <brandVersion-template ref="brandVersionTemplate" @operate="brandVersionOperate"></brandVersion-template>
  </div>
</template>
<script>
import Ready from '_c/modal/ready'
import brandTemplate from './modal/brand-template'
import brandMfTemplate from './modal/brandMf-template'
import brandAudiTemplate from './modal/brandAudi-template'
import brandVersionTemplate from './modal/brandVersion-template'
import { carBrandGet, carBrandCreate, carBrandUpdate, carBrandDel, carBrandMfGet, carBrandMfCreate, carBrandMfUpdate, carBrandMfDel,
  carBrandAudiGet, carBrandAudiCreate, carBrandAudiUpdate, carBrandAudiDel, carBrandVersionGet, carBrandVersionCreate, carBrandVersionUpdate, carBrandVersionDel } from '@/api/data'

export default {
  components: {
    brandVersionTemplate,
    brandAudiTemplate,
    brandMfTemplate,
    brandTemplate,
    Ready
  },
  data () {
    return {
      pageNum: 1,
      // 品牌部分
      BrandItems: [],
      searchBrand: '',
      choiceBrand: '',
      BrandClientId: '',
      // 制作商部分
      BrandMfItems: [],
      searchBrandMf: '',
      choiceBrandMf: '',
      BrandMfClientId: '',
      // 系列部分
      BrandAudiItems: [],
      searchBrandAudi: '',
      choiceBrandAudi: '',
      BrandAudiClientId: '',
      // 系列部分
      BrandVersionItems: [],
      searchBrandVersion: '',
      choiceBrandVersion: '',
      BrandVersionClientId: '',
      // 信息部分
      messageArr: []
    }
  },
  methods: {
    // 初始化页面
    initVue () {
      this.$constant.appendBrand(this, 'BrandItems')
    },
    // 品牌增删改
    brandCreate () {
      this.$refs.brandTemplate.$refs.brandTemplate_form.resetFields()
      this.$refs.brandTemplate.brandTemplate_title = '创建车辆品牌'
      this.$refs.brandTemplate.brandTemplate_modal = true
      this.$refs.brandTemplate.brandTemplate_form.brand = ''
      this.$refs.brandTemplate.brandTemplate_form.abbr = ''
      this.$refs.brandTemplate.brandTemplate_form.initials = ''
      this.$refs.brandTemplate.brandTemplate_judge = 'create'
    },
    brandUpdate () {
      var id = this.choiceBrand
      if (id) {
        carBrandGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            this.$refs.brandTemplate.$refs.brandTemplate_form.resetFields()
            this.$refs.brandTemplate.brandTemplate_title = '修改车辆品牌'
            this.$refs.brandTemplate.brandTemplate_modal = true
            this.$refs.brandTemplate.brandTemplate_id = x.id
            this.$refs.brandTemplate.brandTemplate_form.brand = x.brand
            this.$refs.brandTemplate.brandTemplate_form.abbr = x.abbr
            this.$refs.brandTemplate.brandTemplate_form.initials = x.initials
            this.$refs.brandTemplate.brandTemplate_judge = 'update'
          } else {
            this.$Message.error('该数据为公共数据不可修改!')
          }
        })
      } else {
        this.$Message.error('请先选择需要修改的品牌!')
      }
    },
    brandOperate () {
      var judge = this.$refs.brandTemplate.brandTemplate_judge
      var id = this.$refs.brandTemplate.brandTemplate_id
      var brand = this.$refs.brandTemplate.brandTemplate_form.brand
      var abbr = this.$refs.brandTemplate.brandTemplate_form.abbr
      var initials = this.$refs.brandTemplate.brandTemplate_form.initials
      var data = {
        'brand': brand,
        'abbr': abbr,
        'initials': initials
      }
      if (judge === 'create') {
        this.$refs.brandTemplate.$refs.brandTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            carBrandCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功创建')
                this.$refs.brandTemplate.brandTemplate_modal = false
                this.brandChoice('')
              } else {
                this.$Spin.hide()
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
      } else if (judge === 'update') {
        this.$refs.brandTemplate.$refs.brandTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            carBrandUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                this.$refs.brandTemplate.brandTemplate_modal = false
                this.brandChoice('')
              } else {
                this.$Spin.hide()
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
      } else {}
    },
    brandDel () {
      var id = this.choiceBrand
      if (id) {
        carBrandGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            var name = this.$refs['brand_' + id]['0'].textContent
            this.$refs.ready.ready_title = '删除车辆品牌'
            this.$refs.ready.ready_modal = true
            this.$refs.ready.ready_content = '是否删除-' + name
            this.$refs.ready.ready_params = { 'id': 'brand_' + id }
          } else {
            this.$Message.error('该数据为公共数据不可删除!')
          }
        })
      } else {
        this.$Message.error('请先选择需要删除的品牌!')
      }
    },
    // 制作商增删改
    brandMfCreate () {
      var brand = this.choiceBrand
      if (brand) {
        this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.resetFields()
        this.$refs.brandMfTemplate.brandMfTemplate_title = '创建车辆制造商'
        this.$refs.brandMfTemplate.brandMfTemplate_modal = true
        this.$refs.brandMfTemplate.brandMfTemplate_form.brand = brand
        this.$refs.brandMfTemplate.brandMfTemplate_form.brandMf = ''
        this.$refs.brandMfTemplate.brandMfTemplate_form.abbr = ''
        this.$refs.brandMfTemplate.brandMfTemplate_form.initials = ''
        this.$refs.brandMfTemplate.brandMfTemplate_judge = 'create'
        this.$refs.brandMfTemplate.brandMfTemplate_disabled = true
      } else {
        this.$Message.error('请先选择品牌!')
      }
    },
    brandMfUpdate () {
      var id = this.choiceBrandMf
      if (id) {
        carBrandMfGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.resetFields()
            this.$refs.brandMfTemplate.brandMfTemplate_title = '修改车辆制造商'
            this.$refs.brandMfTemplate.brandMfTemplate_modal = true
            this.$refs.brandMfTemplate.brandMfTemplate_id = x.id
            this.$refs.brandMfTemplate.brandMfTemplate_form.brandMf = x.brandMf
            this.$refs.brandMfTemplate.brandMfTemplate_form.brand = x.brand
            this.$refs.brandMfTemplate.brandMfTemplate_form.abbr = x.abbr
            this.$refs.brandMfTemplate.brandMfTemplate_form.initials = x.initials
            this.$refs.brandMfTemplate.brandMfTemplate_judge = 'update'
            this.$refs.brandMfTemplate.brandMfTemplate_disabled = true
          } else {
            this.$Message.error('该数据为公共数据不可修改!')
          }
        })
      } else {
        this.$Message.error('请先选择需要修改的制造商!')
      }
    },
    brandMfOperate () {
      var judge = this.$refs.brandMfTemplate.brandMfTemplate_judge
      var id = this.$refs.brandMfTemplate.brandMfTemplate_id
      var brand = this.$refs.brandMfTemplate.brandMfTemplate_form.brand
      var brandMf = this.$refs.brandMfTemplate.brandMfTemplate_form.brandMf
      var abbr = this.$refs.brandMfTemplate.brandMfTemplate_form.abbr
      var initials = this.$refs.brandMfTemplate.brandMfTemplate_form.initials
      var data = {
        'brand': brand,
        'brandMf': brandMf,
        'abbr': abbr,
        'initials': initials
      }
      if (judge === 'create') {
        this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            carBrandMfCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功创建')
                this.$refs.brandMfTemplate.brandMfTemplate_modal = false
                this.brandMfChoice('')
              } else {
                this.$Spin.hide()
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
      } else if (judge === 'update') {
        this.$refs.brandMfTemplate.$refs.brandMfTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            carBrandMfUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                this.$refs.brandMfTemplate.brandMfTemplate_modal = false
                this.brandMfChoice('')
              } else {
                this.$Spin.hide()
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
      } else {}
    },
    brandMfDel () {
      var id = this.choiceBrandMf
      if (id) {
        carBrandMfGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            var name = this.$refs['brandMf_' + id]['0'].textContent
            this.$refs.ready.ready_title = '删除车辆制造商'
            this.$refs.ready.ready_modal = true
            this.$refs.ready.ready_content = '是否删除-' + name
            this.$refs.ready.ready_params = { 'id': 'brandMf_' + id }
          } else {
            this.$Message.error('该数据为公共数据不可删除!')
          }
        })
      } else {
        this.$Message.error('请先选择需要删除的制造商!')
      }
    },
    // 系列增删改
    brandAudiCreate () {
      var brandMf = this.choiceBrandMf
      if (brandMf) {
        this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.resetFields()
        this.$refs.brandAudiTemplate.brandAudiTemplate_title = '新增车辆系列'
        this.$refs.brandAudiTemplate.brandAudiTemplate_modal = true
        this.$refs.brandAudiTemplate.brandAudiTemplate_form.carSeries = ''
        this.$refs.brandAudiTemplate.brandAudiTemplate_form.brandMf = brandMf
        this.$refs.brandAudiTemplate.brandAudiTemplate_form.abbr = ''
        this.$refs.brandAudiTemplate.brandAudiTemplate_form.initials = ''
        this.$refs.brandAudiTemplate.brandAudiTemplate_judge = 'create'
        this.$refs.brandAudiTemplate.brandAudiTemplate_disabled = true
      } else {
        this.$Message.error('请先选择制作商!')
      }
    },
    brandAudiUpdate () {
      var id = this.choiceBrandAudi
      if (id) {
        carBrandAudiGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.resetFields()
            this.$refs.brandAudiTemplate.brandAudiTemplate_title = '修改车辆系列'
            this.$refs.brandAudiTemplate.brandAudiTemplate_modal = true
            this.$refs.brandAudiTemplate.brandAudiTemplate_id = x.id
            this.$refs.brandAudiTemplate.brandAudiTemplate_form.brandMf = x.brandMf
            this.$refs.brandAudiTemplate.brandAudiTemplate_form.carSeries = x.carSeries
            this.$refs.brandAudiTemplate.brandAudiTemplate_form.abbr = x.abbr
            this.$refs.brandAudiTemplate.brandAudiTemplate_form.initials = x.initials
            this.$refs.brandAudiTemplate.brandAudiTemplate_judge = 'update'
            this.$refs.brandAudiTemplate.brandAudiTemplate_disabled = true
          } else {
            this.$Message.error('该数据为公共数据不可修改!')
          }
        })
      } else {
        this.$Message.error('请先选择需要修改的制造商!')
      }
    },
    brandAudiOperate () {
      var judge = this.$refs.brandAudiTemplate.brandAudiTemplate_judge
      var id = this.$refs.brandAudiTemplate.brandAudiTemplate_id
      var carSeries = this.$refs.brandAudiTemplate.brandAudiTemplate_form.carSeries
      var brandMf = this.$refs.brandAudiTemplate.brandAudiTemplate_form.brandMf
      var abbr = this.$refs.brandAudiTemplate.brandAudiTemplate_form.abbr
      var initials = this.$refs.brandAudiTemplate.brandAudiTemplate_form.initials
      var data = {
        'carSeries': carSeries,
        'brandMf': brandMf,
        'abbr': abbr,
        'initials': initials
      }
      if (judge === 'create') {
        this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            carBrandAudiCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功创建')
                this.$refs.brandAudiTemplate.brandAudiTemplate_modal = false
                this.brandAudiChoice('')
              } else {
                this.$Spin.hide()
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
      } else if (judge === 'update') {
        this.$refs.brandAudiTemplate.$refs.brandAudiTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            carBrandAudiUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                this.$refs.brandAudiTemplate.brandAudiTemplate_modal = false
                this.brandAudiChoice('')
              } else {
                this.$Spin.hide()
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
      } else {}
    },
    brandAudiDel () {
      var id = this.choiceBrandAudi
      if (id) {
        carBrandAudiGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            var name = this.$refs['brandAudi_' + id]['0'].textContent
            this.$refs.ready.ready_title = '删除车辆系列'
            this.$refs.ready.ready_modal = true
            this.$refs.ready.ready_content = '是否删除-' + name
            this.$refs.ready.ready_params = { 'id': 'brandAudi_' + id }
          } else {
            this.$Message.error('该数据为公共数据不可删除!')
          }
        })
      } else {
        this.$Message.error('请先选择需要删除的系列!')
      }
    },
    // 型号增删改
    brandVersionCreate () {
      var brandAudi = this.choiceBrandAudi
      if (brandAudi) {
        this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.resetFields()
        this.$refs.brandVersionTemplate.brandVersionTemplate_title = '创建车辆型号'
        this.$refs.brandVersionTemplate.brandVersionTemplate_modal = true
        this.$refs.brandVersionTemplate.brandVersionTemplate_form.audi = brandAudi
        this.$refs.brandVersionTemplate.brandVersionTemplate_form.version = ''
        this.$refs.brandVersionTemplate.brandVersionTemplate_form.vName = ''
        this.$refs.brandVersionTemplate.brandVersionTemplate_judge = 'create'
        this.$refs.brandVersionTemplate.brandVersionTemplate_disabled = true
      } else {
        this.$Message.error('请先选择系列!')
      }
    },
    brandVersionUpdate () {
      var id = this.choiceBrandVersion
      if (id) {
        carBrandVersionGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.resetFields()
            this.$refs.brandVersionTemplate.brandVersionTemplate_title = '修改车辆型号'
            this.$refs.brandVersionTemplate.brandVersionTemplate_modal = true
            this.$refs.brandVersionTemplate.brandVersionTemplate_id = x.id
            this.$refs.brandVersionTemplate.brandVersionTemplate_form.audi = x.audi
            this.$refs.brandVersionTemplate.brandVersionTemplate_form.version = x.version
            this.$refs.brandVersionTemplate.brandVersionTemplate_form.vName = x.vName
            this.$refs.brandVersionTemplate.brandVersionTemplate_judge = 'update'
            this.$refs.brandVersionTemplate.brandVersionTemplate_disabled = true
          } else {
            this.$Message.error('该数据为公共数据不可修改!')
          }
        })
      } else {
        this.$Message.error('请先选择需要修改的制造商!')
      }
    },
    brandVersionOperate () {
      var judge = this.$refs.brandVersionTemplate.brandVersionTemplate_judge
      var id = this.$refs.brandVersionTemplate.brandVersionTemplate_id
      var audi = this.$refs.brandVersionTemplate.brandVersionTemplate_form.audi
      var version = this.$refs.brandVersionTemplate.brandVersionTemplate_form.version
      var vName = this.$refs.brandVersionTemplate.brandVersionTemplate_form.vName
      var data = {
        'audi': audi,
        'version': version,
        'vName': vName
      }
      if (judge === 'create') {
        this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            carBrandVersionCreate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功创建')
                this.$refs.brandVersionTemplate.brandVersionTemplate_modal = false
                this.brandVersionChoice('')
              } else {
                this.$Spin.hide()
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
      } else if (judge === 'update') {
        this.$refs.brandVersionTemplate.$refs.brandVersionTemplate_form.validate((valid) => {
          if (valid) {
            this.$Spin.show()
            data['id'] = id
            carBrandVersionUpdate(data).then(res => {
              if (res.data.msg === 'ok') {
                this.$Spin.hide()
                this.$Message.success('成功修改')
                this.$refs.brandVersionTemplate.brandVersionTemplate_modal = false
                this.brandVersionChoice('')
              } else {
                this.$Spin.hide()
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
      } else {}
    },
    brandVersionDel () {
      var id = this.choiceBrandVersion
      if (id) {
        carBrandVersionGet(id).then(res => {
          var x = res.data.data
          var clientId = x.clientId
          if (clientId) {
            var name = this.$refs['brandVersion_' + id]['0'].textContent
            this.$refs.ready.ready_title = '删除车辆系列'
            this.$refs.ready.ready_modal = true
            this.$refs.ready.ready_content = '是否删除-' + name
            this.$refs.ready.ready_params = { 'id': 'brandVersion_' + id }
          } else {
            this.$Message.error('该数据为公共数据不可删除!')
          }
        })
      } else {
        this.$Message.error('请先选择需要删除的系列!')
      }
    },
    // 统一删除
    ParentNext () {
      this.$Spin.show()
      var arr = this.$refs.ready.ready_params.id.split('_')
      var type = arr[0]
      var id = arr[1]
      if (type === 'brand') {
        carBrandDel(id).then(res => {
          this.$Spin.hide()
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功删除')
          this.brandChoice('')
        })
      } else if (type === 'brandMf') {
        carBrandMfDel(id).then(res => {
          this.$Spin.hide()
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功删除')
          this.brandMfChoice('')
        })
      } else if (type === 'brandAudi') {
        carBrandAudiDel(id).then(res => {
          this.$Spin.hide()
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功删除')
          this.brandAudiChoice('')
        })
      } else {
        carBrandVersionDel(id).then(res => {
          this.$Spin.hide()
          this.$refs.ready.ready_modal = false
          this.$Message.success('成功删除')
          this.brandVersionChoice('')
        })
      }
    },
    // 选中的品牌id
    brandChoice (x) {
      this.choiceBrand = x
      if (x) {
        var s_arr = [{ 'key': 'brand', 'value': x }]
        this.$constant.appendBrandMf(this, 'BrandMfItems', s_arr)
        carBrandGet(x).then(res => {
          var result = res.data.data
          this.BrandClientId = result.clientId
          var Rarr = [
            { 'key': '品牌', 'value': result.brand },
            { 'key': '缩写', 'value': result.abbr },
            { 'key': '首字母', 'value': result.initials }
          ]
          this.messageArr = Rarr
        })
      } else {
        this.$constant.appendBrand(this, 'BrandItems')
        this.searchBrand = ''
        this.choiceBrand = ''
        this.BrandMfItems = []
      }
      // 清空
      this.searchBrandMf = ''
      this.choiceBrandMf = ''
      this.BrandAudiItems = []
      this.searchBrandAudi = ''
      this.choiceBrandAudi = ''
      this.BrandVersionItems = []
      this.searchBrandVersion = ''
      this.choiceBrandVersion = ''
    },
    // 选中的制作商id
    brandMfChoice (x) {
      this.choiceBrandMf = x
      if (x) {
        var s_arr = [{ 'key': 'brandMf', 'value': x }]
        this.$constant.appendBrandAudi(this, 'BrandAudiItems', s_arr)
        carBrandMfGet(x).then(res => {
          var result = res.data.data
          this.BrandMfClientId = result.clientId
          var Rarr = [
            { 'key': '制造商', 'value': result.brandMf },
            { 'key': '缩写', 'value': result.abbr },
            { 'key': '首字母', 'value': result.initials }
          ]
          this.messageArr = Rarr
        })
      } else {
        var brand_id = this.choiceBrand
        var a_arr = [{ 'key': 'brand', 'value': brand_id }]
        this.$constant.appendBrandMf(this, 'BrandMfItems', a_arr)
        this.searchBrandMf = ''
        this.choiceBrandMf = ''
        this.BrandAudiItems = []
      }
      // 清空
      this.searchBrandAudi = ''
      this.choiceBrandAudi = ''
      this.BrandVersionItems = []
      this.searchBrandVersion = ''
      this.choiceBrandVersion = ''
    },
    // 选中的系列id
    brandAudiChoice (x) {
      this.choiceBrandAudi = x
      if (x) {
        var s_arr = [{ 'key': 'audi', 'value': x }]
        this.$constant.appendBrandVersion(this, 'BrandVersionItems', s_arr)
        carBrandAudiGet(x).then(res => {
          var result = res.data.data
          this.BrandAudiClientId = result.clientId
          var Rarr = [
            { 'key': '系列', 'value': result.carSeries },
            { 'key': '缩写', 'value': result.abbr },
            { 'key': '首字母', 'value': result.initials }
          ]
          this.messageArr = Rarr
        })
      } else {
        var brandMf_id = this.choiceBrandMf
        var a_arr = [{ 'key': 'brandMf', 'value': brandMf_id }]
        this.$constant.appendBrandAudi(this, 'BrandAudiItems', a_arr)
        this.searchBrandAudi = ''
        this.choiceBrandAudi = ''
        this.BrandVersionItems = []
      }
      // 清空
      this.searchBrandVersion = ''
      this.choiceBrandVersion = ''
    },
    // 选中的型号id
    brandVersionChoice (x) {
      this.choiceBrandVersion = x
      if (x) {
        carBrandVersionGet(x).then(res => {
          var result = res.data.data
          this.BrandVersionClientId = result.clientId
          var Rarr = [
            { 'key': '车型规格', 'value': result.version },
            { 'key': '车型名称', 'value': result.vName }
          ]
          this.messageArr = Rarr
        })
      } else {
        var audi_id = this.choiceBrandAudi
        var s_arr = [{ 'key': 'audi', 'value': audi_id }]
        this.$constant.appendBrandVersion(this, 'BrandVersionItems', s_arr)
        this.searchBrandVersion = ''
        this.choiceBrandVersion = ''
      }
    }
  },
  computed: {
    NewBrandItems () {
      var _this = this
      var NewBrandItems = []
      this.BrandItems.map(function (item) {
        if (item.value.search(_this.searchBrand) !== -1) {
          NewBrandItems.push(item)
        }
      })
      return NewBrandItems
    },
    NewBrandMfItems () {
      var _this = this
      var NewBrandMfItems = []
      this.BrandMfItems.map(function (item) {
        if (item.value.search(_this.searchBrandMf) !== -1) {
          NewBrandMfItems.push(item)
        }
      })
      return NewBrandMfItems
    },
    NewBrandAudiItems () {
      var _this = this
      var NewBrandAudiItems = []
      this.BrandAudiItems.map(function (item) {
        if (item.value.search(_this.searchBrandAudi) !== -1) {
          NewBrandAudiItems.push(item)
        }
      })
      return NewBrandAudiItems
    },
    NewBrandVersionItems () {
      var _this = this
      var NewBrandVersionItems = []
      this.BrandVersionItems.map(function (item) {
        if (item.value.search(_this.searchBrandVersion) !== -1) {
          NewBrandVersionItems.push(item)
        }
      })
      return NewBrandVersionItems
    }
  },
  mounted () {
    this.initVue()
  }
}
</script>

<style scoped>
  .linkage_icon{
    text-align: center;
  }
  .linkage_icon i{
    font-size: 28px;
  }
  /deep/ .linkage_div .ivu-card-body{
    padding: 6px;
  }
  /deep/ .linkage_div .ivu-card-bordered{
    border: 0.08rem solid #dcdee2;
    border-radius: 6px 6px 0 0;
  }
  /deep/ .linkage_div .ivu-card-head{
    background: #f9fafc;
    border-radius: 6px 6px 0 0;
    text-align: center;
  }
  /deep/ .ivu-input-search-icon{
    cursor: auto;
  }
  .linkage_div p{
    color: #77797b;
  }
  .linkage_content_ul{
    border: 1px solid #dcdee2;
    margin: 5px  0;
    height: 325px;
    overflow: auto;
  }
  .linkage_content_ul li:hover{
    background: #e8e8e8;
  }
  .linkage_operate{
  margin: 10px 0;
  }
  .linkage_operate button{
    margin: 2px;
  }
  .choice{
    background: #e4e4e4;
  }
  .message_div{
    margin: 10px 0;
  }
</style>
