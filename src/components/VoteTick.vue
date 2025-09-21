<template>
  <div class="vote-tick-container">
    <div class="question-header">
      <h2 class="question-title">{{ question.title }}</h2>
    </div>
    <div class="info-section">
      <a class="copyright-link" :href="globalStore.copyrightLink">{{ globalStore.copyright }}</a>
      <span
        class="statistics-info"
        v-html="$t('vote.pub_author', { totalNum: totalNum, rightNum: rightNum })"
      ></span>
    </div>
    <var-list class="options-list">
      <var-button
        class="option-button"
        v-for="(item, i) in question.items"
        :key="item.opt"
        @click="toTick(item.opt)"
        block
        ripple
      >
        <span class="option-label">{{ opts[i] }}.</span>
        <span class="option-content">{{ item.content }}</span>
      </var-button>
    </var-list>
  </div>
</template>

<script>
import { useGlobalStore } from '../stores/global';
import { useApiStore } from '../stores/api';
import { Dialog } from '@varlet/ui';

export default {
  created() {
    this.globalStore = useGlobalStore();
    const apiStore = useApiStore();
    this.globalStore.setTitle(this.$t('vote.title'));
    this.globalStore.setShowBack(false);
    this.globalStore.setShowMore(false);
    var baseUrl = apiStore.baseUrl;
    var jiacn = this.globalStore.getJiacn;
    const _this = this;
    this.$http
      .get(baseUrl + '/vote/get/random', {
        params: {
          jiacn: jiacn
        }
      })
      .then((res) => {
        _this.question = res.data.data;
        for (var i = 0; i < _this.question.items.length; i++) {
          _this.totalNum += _this.question.items[i].num;
          if (_this.question.items[i].tick === 1) {
            _this.rightNum = _this.question.items[i].num;
          }
        }
      });
  },
  methods: {
    onClickOpMenu(key, item) {
      console.log(item);
    },
    toTick(opt) {
      const apiStore = useApiStore();
      var baseUrl = apiStore.baseUrl;
      var jiacn = this.globalStore.getJiacn;
      const _this = this;
      this.$http
        .post(baseUrl + '/vote/tick', {
          jiacn: jiacn,
          questionId: this.question.id,
          opt: opt
        })
        .then((res) => {
          if (res.data.data) {
            Dialog({
              title: _this.$t('app.notify'),
              message: _this.$t('vote.right_alert', {
                point: _this.question.point
              }),
              confirmButtonText: _this.$t('app.confirm'),
              onConfirm: () => {
                _this.$router.go(0);
              }
            });
          } else {
            Dialog({
              title: _this.$t('app.alert'),
              message: _this.$t('vote.wrong_alert', {
                opt: _this.question.opt
              }),
              confirmButtonText: _this.$t('app.confirm')
            });
          }
        });
    }
  },
  data() {
    return {
      question: {},
      showOpMenu: false,
      opts: ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'],
      totalNum: 0,
      rightNum: 0
    };
  }
};
</script>
<style scoped>
.vote-tick-container {
  padding: 16px;
  overflow-y: auto;
  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
}

.question-header {
  margin: 0 16px;
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: white;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  padding: 24px;
  margin-bottom: 20px;
}

.question-title {
  font-size: 18px;
  font-weight: 600;
  color: #2c3e50;
  text-align: center;
  line-height: 1.5;
  margin: 0;
}

.info-section {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 12px;
}

.copyright-link {
  color: #576b95;
  text-decoration: none;
  font-size: 12px;
  transition: color 0.3s ease;
}

.copyright-link:hover {
  color: #3498db;
  text-decoration: underline;
}

.statistics-info {
  color: #7f8c8d;
  font-size: 12px;
  display: inline-block;
}

.options-list {
  margin: 0;
}

.option-button {
  text-align: left;
  margin-bottom: 12px;
  border-radius: 8px;
  border: 1px solid #e0e0e0;
  background: white;
  transition: all 0.3s ease;
  padding: 16px;
  height: auto;
  min-height: 60px;
}

.option-button:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.15);
  border-color: #3498db;
}

.option-label {
  font-weight: bold;
  color: #3498db;
  margin-right: 8px;
  font-size: 16px;
}

.option-content {
  color: #2c3e50;
  font-size: 14px;
  line-height: 1.4;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .vote-tick-container {
    padding: 12px;
  }

  .question-header {
    margin: 0 8px 20px 8px;
    min-height: 160px;
    padding: 16px;
  }

  .question-title {
    font-size: 16px;
  }

  .info-section {
    flex-direction: row;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  .option-button {
    padding: 12px;
    min-height: 50px;
  }

  .option-label {
    font-size: 14px;
  }

  .option-content {
    font-size: 13px;
  }
}

@media (max-width: 480px) {
  .question-header {
    margin: 0 8px 16px 8px;
    min-height: 120px;
    padding: 12px;
  }

  .question-title {
    font-size: 15px;
  }

  .option-button {
    padding: 10px;
    min-height: 45px;
  }
}
</style>
