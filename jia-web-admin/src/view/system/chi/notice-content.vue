<template>
  <div>
    <Card>
      <div class="page_wrap">
        <div class="page_header">
          <h1 class="page_title">{{page.title}}</h1>
          <p class="page_updateTime">{{page.updateTime}}</p>
        </div>
        <div class="page_content" ref="page_content">
          <p>
            我们的驾驶证从“初次领证日期”起算，每一年为一个记分周期，如果违法将会扣除相对应的数值。如果在一个记分周期内记满12分的，需要参加学习并考试，考试通过后驾驶证才能正常使用。但有种情况出现的违章并不用自己承担，那就是车辆被人套牌了。
          </p>
        </div>
      </div>
    </Card>
  </div>
</template>

<script>
import { getNotice } from '@/api/data'

export default {
  components: {

  },
  data () {
    return {
      page: {
        title: '',
        content: '',
        updateTime: ''
      }
    }
  },
  methods: {
    // 获取公告信息
    NoticeGet (id) {
      getNotice(id).then(res => {
        var data = res.data.data
        this.page.title = data.title
        this.page.updateTime = this.$constant.formatDateTime(data.updateTime)
        this.page.content = data.content
        var parent = this.$refs.page_content
        this.$constant.append(parent, data.content)
      })
    }
  },
  mounted () {
    var id = this.$route.params.id
    this.NoticeGet(id)
  },
  watch: {
    '$route' (to, from) {
      if (this.$route.params.id) {
        var id = this.$route.params.id
        this.NoticeGet(id)
      }
    }
  }
}
</script>

<style scoped>
  .page_wrap{
  text-align: center;
    min-height: 650px;
    /*overflow: auto;*/
  }
  .page_header{
    margin: 10px 0;
    border-bottom: 1px solid #e3e3e3;
  }
  .page_content{
    text-align: left;
    padding: 10px;
  }
</style>
