<template>
  <div class="custom-bread-crumb">
    <div class="logo-con" v-show="showLogo">
      <img  :src="orgLogo"/>
    </div>
    <Breadcrumb :style="{fontSize: `${fontSize}px`}" v-show="!showLogo">
      <BreadcrumbItem v-for="item in list" :to="item.to" :key="`bread-crumb-${item.name}`">
        <common-icon style="margin-right: 4px;" :type="item.icon || ''"/>
        {{ showTitle(item) }}
      </BreadcrumbItem>
    </Breadcrumb>
  </div>
</template>
<script>
import maxLogo from '@/assets/images/logo.jpg'
import { showTitle } from '@/libs/util'
import CommonIcon from '_c/common-icon'
import './custom-bread-crumb.less'
export default {
  name: 'customBreadCrumb',
  components: {
    CommonIcon
  },
  data () {
    return {
      maxLogo,
      showLogo: false
    }
  },
  props: {
    list: {
      type: Array,
      default: () => []
    },
    fontSize: {
      type: Number,
      default: 14
    },
    showIcon: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    orgLogo () {
      var orgLogo = this.$store.state.user.orgDate.logo
      if (orgLogo) {
        orgLogo = this.$constant.StaticUrl + orgLogo
      } else {
        orgLogo = this.maxLogo
      }
      return orgLogo
    }
  },
  methods: {
    showTitle (item) {
      return showTitle(item, this)
    },
    isCustomIcon (iconName) {
      return iconName.indexOf('_') === 0
    },
    getCustomIconName (iconName) {
      return iconName.slice(1)
    }
  }
}
</script>
