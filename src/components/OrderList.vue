<template>
  <div>
    <var-action-sheet :actions="opMenu" v-model="showOpMenu" @select="onClickOpMenu" />

    <var-list>
      <var-cell v-for="item in list" :key="item.id" @click="doShowOpMenu(item)">
        <template #title>
          {{ item.title }}
        </template>
        <template #description>
          {{ item.desc }}
        </template>
        <template #extra>
          <div style="font-size: 12px; color: #999">
            <div>{{ item.meta.source }}</div>
            <div>{{ item.meta.date }}</div>
            <div>{{ item.meta.other }}</div>
          </div>
        </template>
      </var-cell>
    </var-list>
  </div>
</template>

<script>
import dayjs from 'dayjs';
import { useGlobalStore } from '@/stores/global';
import { useApiStore } from '@/stores/api';
import { useUtilStore } from '@/stores/util';

export default {
  setup() {
    const globalStore = useGlobalStore();
    const apiStore = useApiStore();
    const utilStore = useUtilStore();
    return { globalStore, apiStore, utilStore };
  },
  created() {
    this.globalStore.setTitle(this.$t('gift.order_list'));
    this.globalStore.setShowBack(true);
    this.globalStore.setShowMore(false);
    var baseUrl = this.apiStore.baseUrl;
    var jiacn = this.globalStore.getJiacn;
    this.$http
      .post(baseUrl + '/gift/usage/list/user/' + jiacn, {
        pageNum: 1,
        pageSize: 999
      })
      .then((res) => {
        const _this = this;
        this.list = [];
        res.data.data.forEach((element) => {
          let orderItem = {
            id: element.id,
            title: element.name,
            desc: element.description,
            status: element.status,
            meta: {
              source: element.point ? element.point + _this.$t('gift.point') : '￥' + element.price,
              date: dayjs(this.utilStore.fromTimeStamp(element.time)).format('YYYY-MM-DD'),
              other:
                _this.$t('gift.quantity_title', {
                  quantity: element.quantity
                }) +
                '　' +
                _this.statusMap[element.status]
            }
          };
          this.list.push(orderItem);
        });
      });
  },
  methods: {
    doShowOpMenu(item) {
      this.selectId = item.id;
      if (item.status === 1) {
        this.opMenu = [{ key: 'cancel', name: this.$t('gift.cancel') }];
      } else if (item.status === 0 || item.status === 5) {
        this.opMenu = [{ key: 'del', name: this.$t('gift.del') }];
      }
      this.showOpMenu = true;
    },
    onClickOpMenu(item) {
      if (item.key === 'del') {
        const _this = this;
        Dialog({
          title: _this.$t('gift.del_alert'),
          message: '',
          onConfirm: () => {
            var baseUrl = _this.apiStore.baseUrl;
            _this.$http.post(baseUrl + '/gift/usage/delete/' + _this.selectId, {}).then((res) => {
              if (res.data.code === 'E0') {
                Dialog({
                  title: _this.$t('app.notify'),
                  message: res.data.msg,
                  onConfirm: () => {
                    _this.$router.go(0);
                  }
                });
              } else {
                Dialog({
                  title: _this.$t('app.alert'),
                  message: res.data.msg
                });
              }
            });
          }
        });
      } else if (item.key === 'cancel') {
        const _this = this;
        Dialog({
          title: _this.$t('gift.cancel_alert'),
          message: '',
          onConfirm: () => {
            var baseUrl = _this.apiStore.baseUrl;
            _this.$http.post(baseUrl + '/gift/usage/cancel/' + _this.selectId, {}).then((res) => {
              if (res.data.code === 'E0') {
                Dialog({
                  title: _this.$t('app.notify'),
                  message: res.data.msg,
                  onConfirm: () => {
                    _this.$router.go(0);
                  }
                });
              } else {
                Dialog({
                  title: _this.$t('app.alert'),
                  message: res.data.msg
                });
              }
            });
          }
        });
      }
    }
  },
  data() {
    return {
      list: [],
      opMenu: [],
      showOpMenu: false,
      selectId: 0,
      statusMap: {
        0: this.$t('order.status_unpaid'),
        1: this.$t('order.status_paid'),
        5: this.$t('order.status_canceled')
      }
    };
  }
};
</script>
