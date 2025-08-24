<template>
  <div class="phrase-container">
    <var-dialog v-model="addDialogShow">
      <div class="cell-group">
        <var-input 
          type="textarea" 
          :placeholder="$t('phrase.content_placeholder')" 
          :maxlength="200" 
          v-model="newContent">
        </var-input>
        <var-button 
          type="primary" 
          block
          :disabled="newContent==''" 
          @click="addContent">
          {{ $t('app.submit') }}
        </var-button>
        <var-button 
          type="default" 
          block
          @click="addDialogShow=false">
          {{ $t('app.cancel') }}
        </var-button>
      </div>
    </var-dialog>

    <var-dialog v-model="fbDialogShow">
      <div class="cell-group">
        <var-input 
          :placeholder="$t('phrase.feedback_title_placeholder')" 
          :maxlength="50" 
          v-model="fbTitle">
        </var-input>
        <var-input 
          type="textarea" 
          :placeholder="$t('phrase.feedback_content_placeholder')" 
          :maxlength="500" 
          v-model="fbContent">
        </var-input>
        <var-input 
          :placeholder="$t('phrase.feedback_name_placeholder')" 
          :maxlength="20" 
          v-model="fbName">
        </var-input>
        <var-input 
          :placeholder="$t('phrase.feedback_phone_placeholder')" 
          type="tel" 
          v-model="fbPhone"
          :rules="[(v) => /^1[3-9]\d{9}$/.test(v) || '请输入正确手机号']">
        </var-input>
        <var-input 
          :placeholder="$t('phrase.feedback_email_placeholder')" 
          :maxlength="100" 
          type="email"
          v-model="fbEmail"
          :rules="[(v) => /.+@.+\..+/.test(v) || '请输入正确邮箱']">
        </var-input>
        <var-button 
          type="primary" 
          block
          :disabled="fbTitle==''" 
          @click="feedback">
          {{ $t('app.submit') }}
        </var-button>
        <var-button 
          type="default" 
          block
          @click="fbDialogShow=false">
          {{ $t('app.cancel') }}
        </var-button>
      </div>
    </var-dialog>

    <div class="phrase-content">
      <h2 id="content" class="phrase-text">{{phrase.content}}</h2>
    </div>
    <div class="phrase-meta">
      <span class="meta-text">
        阅读{{phrase.pv}} {{author}} <a :href="globalStore.copyrightLink" class="copyright-link">{{globalStore.copyright}}</a> 发布于{{formatTime(phrase.createTime)}}
      </span>
    </div>
    
    <div class="action-grid">
      <div class="action-item" @click="payTips">
          <var-icon name="heart" size="26px" class="action-icon"/>
          <div class="action-label">{{ $t('phrase.tips') }}</div>
      </div>
      <div class="action-item" ref="upvote" @click="toTick(1)">
        <var-icon name="thumb-up" size="26px" class="action-icon"/>
        <div class="action-label">{{ $t('phrase.up') }}{{phrase.up}}</div>
      </div>
      <div class="action-item" ref="downvote" @click="toTick(0)">
        <var-icon name="thumb-down" size="26px" class="action-icon"/>
        <div class="action-label">{{ $t('phrase.down') }}{{phrase.down}}</div>
      </div>
      <div class="action-item" @click="fbDialogShow=true">
        <var-icon name="chat-processing" size="26px" class="action-icon"/>
        <div class="action-label">{{ $t('phrase.say') }}</div>
      </div>
    </div>

    <div class="section-title">
      <h5>{{ $t('phrase.others') }}</h5>
    </div>

    <div class="action-grid">
      <div class="action-item" id="copyBtn" @click="copyContent">
        <var-icon name="content-copy" size="26px" class="action-icon"/>
        <div class="action-label">{{ $t('phrase.copy') }}</div>
      </div>
      <div class="action-item" @click="refreshPage">
        <var-icon name="refresh" size="26px" class="action-icon"/>
        <div class="action-label">{{ $t('phrase.next') }}</div>
      </div>
      <div class="action-item" @click="addDialogShow=true">
        <var-icon name="plus" size="26px" class="action-icon"/>
        <div class="action-label">{{ $t('phrase.add') }}</div>
      </div>
      <div class="action-item" @click="closeWindow">
        <var-icon name="close" size="26px" class="action-icon"/>
        <div class="action-label">{{ $t('phrase.close') }}</div>
      </div>
    </div>
  </div>
</template>

<script>
import Clipboard from 'clipboard'
import { useGlobalStore } from '../stores/global'
import { useApiStore } from '../stores/api'
import { useUtilStore } from '../stores/util'

export default {
  created: function () {
    this.globalStore = useGlobalStore()
    const utilStore = useUtilStore()
    this.globalStore.setTitle(this.$t('phrase.title'))
    document.title = this.$t('phrase.title_sub')
    this.globalStore.setShowBack(false)
    this.globalStore.setShowMore(false)
    const apiStore = useApiStore()
    this.utilStore = utilStore
    var baseUrl = apiStore.baseUrl
    var jiacn = this.globalStore.getJiacn
    const _this = this
    this.$http.post(baseUrl + '/phrase/get/random', {
      jiacn: jiacn
    }).then(res => {
      _this.phrase = res.data.data
      document.title = _this.phrase.content
      this.$http.get(baseUrl + '/phrase/read', {
        params: {
          id: res.data.data.id
        }
      }).then(res => {
        _this.phrase.pv++
      })
      if (_this.phrase.jiacn) {
        this.$http.get(baseUrl + '/user/get', {
          params: {
            type: 'cn',
            key: _this.phrase.jiacn
          }
        }).then(res => {
          if (res.data.code === 'E0') {
            _this.author = res.data.data.nickname
          }
        })
      }
    })
  },
  methods: {
    toTick: function (opt) {
      if (this.hasTick) return false
      this.hasTick = true
      const apiStore = useApiStore()
      var baseUrl = apiStore.baseUrl
      var jiacn = this.globalStore.getJiacn
      const _this = this
      if (!jiacn) {
        Dialog({
          title: _this.$t('app.notify'),
          message: _this.$t('phrase.subscribe_notify'),
          onConfirm: () => {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return false
      }
      this.$http.post(baseUrl + '/phrase/vote', {
        jiacn: jiacn,
        phraseId: this.phrase.id,
        vote: opt
      }).then(res => {
        if (res.data.code === 'E0') {
          if (opt === 1) {
            _this.phrase.up++
            _this.$refs.upvote.$el.classList.add('voted')
          } else {
            _this.phrase.down++
            _this.$refs.downvote.$el.classList.add('voted')
          }
        }
      })
    },
    copyContent: function () {
      var clipboard = new Clipboard('#copyBtn')
      const _this = this
      clipboard.on('success', function (e) {
        Dialog({
          title: _this.$t('app.notify'),
          message: _this.$t('phrase.copy_success')
        })
        e.clearSelection()
      })
    },
    refreshPage: function () {
      window.history.go(0)
    },
    closeWindow: function () {
      const utilStore = useUtilStore()
      utilStore.closeWindow()
    },
    formatTime: function(timestamp) {
      const utilStore = useUtilStore()
      return utilStore.fromTimeStamp(timestamp, 'YYYY-MM-DD')
    },
    addContent: function () {
      const apiStore = useApiStore()
      var baseUrl = apiStore.baseUrl
      var jiacn = this.globalStore.getJiacn
      const _this = this
      this.$http.post(baseUrl + '/phrase/create', {
        jiacn: jiacn,
        content: this.newContent.trim(),
        tag: '毒鸡汤'
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.newContent = ''
          _this.addDialogShow = false
          Dialog({
            title: _this.$t('app.notify'),
            message: _this.$t('phrase.add_success')
          })
        } else {
          _this.addDialogShow = false
          Dialog({ 
            title: _this.$t('app.alert'), 
            message: res.data.msg 
          })
        }
      })
    },
    feedback: function () {
      const apiStore = useApiStore()
      var baseUrl = apiStore.baseUrl
      var jiacn = this.globalStore.getJiacn
      const _this = this
      if (!jiacn) {
        Dialog({
          title: _this.$t('app.notify'),
          message: _this.$t('phrase.subscribe_notify'),
          onConfirm: () => {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return false
      }
      let formData = new FormData()
      formData.append('jiacn', jiacn)
      formData.append('resourceId', 'phrase')
      formData.append('name', _this.fbName)
      formData.append('phone', _this.fbPhone)
      formData.append('email', _this.fbEmail)
      formData.append('title', _this.fbTitle)
      formData.append('content', _this.fbContent)
      this.$http.post(baseUrl + '/kefu/message/create', formData).then(res => {
        if (res.data.code === 'E0') {
          _this.fbTitle = ''
          _this.fbContent = ''
          _this.fbDialogShow = false
          Dialog({
            title: _this.$t('app.notify'),
            message: _this.$t('phrase.feedback_success')
          })
        }
      })
    },
    payTips: function () {
      const apiStore = useApiStore()
      var baseUrl = apiStore.baseUrl
      var jiacn = this.globalStore.getJiacn
      var appid = this.globalStore.user.appid
      const _this = this

      if (!jiacn) {
        Dialog({
          title: _this.$t('app.notify'),
          message: _this.$t('phrase.subscribe_notify'),
          onConfirm: () => {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return false
      }
      this.$http.post(baseUrl + '/tip/create', {
        type: 1,
        entityId: this.phrase.id,
        price: 100,
        jiacn: jiacn,
        status: 0
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.$http.get(baseUrl + '/wx/pay/createOrder', {
            params: {
              outTradeNo: 'TIP' + (Array(7).join('0') + res.data.data.id).slice(-7),
              tradeType: 'JSAPI',
              appid: appid
            }
          }).then((res) => {
            if (res.data) {
              _this.weixinPay(res.data)
            } else {
              Dialog({ 
                title: _this.$t('app.alert'), 
                message: res.data.msg 
              })
            }
          })
        } else {
          Dialog({ 
            title: _this.$t('app.alert'), 
            message: res.data.msg 
          })
        }
      })
    },
    weixinPay: function (data) {
      var vm = this
      if (typeof WeixinJSBridge === 'undefined') {
        if (document.addEventListener) {
          document.addEventListener('WeixinJSBridgeReady', vm.onBridgeReady(data), false)
        } else if (document.attachEvent) {
          document.attachEvent('WeixinJSBridgeReady', vm.onBridgeReady(data))
          document.attachEvent('onWeixinJSBridgeReady', vm.onBridgeReady(data))
        }
      } else {
        vm.onBridgeReady(data)
      }
    },
    onBridgeReady: function (data) {
      var vm = this
      WeixinJSBridge.invoke(
        'getBrandWCPayRequest', {
          debug: true,
          'appId': data.appId,
          'timeStamp': data.timeStamp,
          'nonceStr': data.nonceStr,
          'package': data.packageValue,
          'signType': data.signType,
          'paySign': data.paySign,
          jsApiList: [
            'chooseWXPay'
          ]
        },
        function (res) {
          if (res.err_msg === 'get_brand_wcpay_request:ok') {
            Dialog({
              title: vm.$t('app.notify'),
              message: vm.$t('phrase.pay_notify')
            })
          } else {
            Dialog({ 
              title: vm.$t('app.alert'), 
              message: vm.$t('phrase.pay_cancel') 
            })
          }
        }
      )
    }
  },
  data () {
    return {
      phrase: {},
      showOpMenu: false,
      hasTick: false,
      addDialogShow: false,
      newContent: '',
      author: '',
      fbDialogShow: false,
      fbTitle: '',
      fbContent: '',
      fbName: '',
      fbPhone: '',
      fbEmail: ''
    }
  },
}
</script>

<style scoped>
.phrase-container {
  padding: 20px 0;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  min-height: 100vh;
}

.phrase-content {
  margin: 0 25px;
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  padding: 30px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  margin-bottom: 20px;
  transition: transform 0.2s ease;
  backdrop-filter: blur(10px);
}

.phrase-content:hover {
  transform: translateY(-2px);
}

.phrase-text {
  text-align: center;
  font-size: 1.8rem;
  line-height: 1.6;
  color: #2c3e50;
  font-weight: 500;
  margin: 0;
  word-break: break-word;
}

.phrase-meta {
  margin: 0 25px 25px;
  text-align: center;
}

.meta-text {
  color: #6c757d;
  font-size: 0.9rem;
  display: inline-block;
  padding: 8px 16px;
  border-radius: 20px;
  backdrop-filter: blur(10px);
}

.copyright-link {
  color: #FF9900 !important;
  text-decoration: none;
  transition: color 0.2s ease;
}

.copyright-link:hover {
  color: #e67e22 !important;
  text-decoration: underline;
}

.action-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin: 0 25px 15px;
}

.action-item {
  cursor: pointer;
  padding: 15px 10px;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  transition: all 0.3s ease;
  margin-bottom: 10px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 80px;
  backdrop-filter: blur(10px);
}

.action-item:hover {
  transform: translateY(-3px);
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.12);
  background: #fff8e6;
}

.action-icon {
  color: #FF9900;
  transition: transform 0.2s ease;
  flex-shrink: 0;
}

.action-item:hover .action-icon {
  transform: scale(1.1);
}

.action-label {
  font-size: 0.85rem;
  color: #495057;
  font-weight: 500;
}

.section-title {
  margin: 30px 25px 20px;
  text-align: center;
}

.section-title h5 {
  color: #6c757d;
  font-weight: 600;
  font-size: 1.1rem;
  margin: 0;
  position: relative;
  display: inline-block;
}

.section-title h5::before,
.section-title h5::after {
  content: '';
  position: absolute;
  top: 50%;
  width: 40px;
  height: 1px;
}

.section-title h5::before {
  right: 100%;
  margin-right: 15px;
}

.section-title h5::after {
  left: 100%;
  margin-left: 15px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .phrase-content {
    margin: 0 15px;
    padding: 20px;
  }
  
  .phrase-text {
    font-size: 1.4rem;
  }
  
  .action-grid {
    grid-template-columns: repeat(4, 1fr);
    margin: 0 15px 20px;
    gap: 8px;
  }
  
  .action-item {
    padding: 2px 8px;
    margin-bottom: 0;
  }
  
  .action-label {
    font-size: 0.8rem;
  }
}

@media (max-width: 480px) {
  .phrase-text {
    font-size: 1.2rem;
  }
  
  .action-grid {
    grid-template-columns: repeat(4, 1fr);
    margin: 0 10px 15px;
    gap: 6px;
  }
  
  .action-icon {
    font-size: 22px;
  }
  
  .action-label {
    font-size: 0.75rem;
  }
}

/* 动画效果 */
@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.phrase-content,
.action-item {
  animation: fadeInUp 0.6s ease-out;
}

.action-item:nth-child(1) { animation-delay: 0.1s; }
.action-item:nth-child(2) { animation-delay: 0.2s; }
.action-item:nth-child(3) { animation-delay: 0.3s; }
.action-item:nth-child(4) { animation-delay: 0.4s; }

/* 投票后的样式 */
.action-item.voted .action-icon {
  color: #ff4757 !important;
}

.action-item.voted .action-label {
  color: #ff4757 !important;
  font-weight: 600;
}
</style>
