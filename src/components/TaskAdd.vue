<template>
  <div>
    <div>
      <var-picker
        :title="$t('task.type')"
        :columns="typeList"
        v-model="type"
        @change="changeType"
      />
      <var-picker
        :title="$t('task.period')"
        :columns="periodList"
        v-model="period"
        @change="changePeriod"
      />
      <var-input :label="$t('task.name')" v-model="name" />
      <var-textarea :label="$t('task.description')" v-model="description" :rows="3" />
      <var-date-picker
        v-model="start_time"
        :max-date="new Date(2100, 0, 1)"
        format="YYYY-MM-DD HH:mm"
        :minute-list="['00', '15', '30', '45']"
        :title="$t('task.start_time')"
        v-show="startTimeShow"
      />
      <var-date-picker
        v-model="end_time"
        :max-date="new Date(2100, 0, 1)"
        format="YYYY-MM-DD HH:mm"
        :minute-list="['00', '15', '30', '45']"
        :title="$t('task.end_time')"
        v-show="endTimeShow"
      />
      <var-switch :label="$t('task.lunar')" v-model="lunar" :active-value="1" :inactive-value="0" />
      <var-input
        :label="$t('task.amount')"
        v-model="amount"
        type="tel"
        :maxlength="6"
        v-show="amountShow"
      />
      <var-switch
        :label="$t('task.remind')"
        v-model="remind"
        :active-value="1"
        :inactive-value="0"
      />
      <br />
      <var-button type="primary" @click="doAdd">{{ $t('app.save') }}</var-button>
    </div>
  </div>
</template>

<script>
import { useGlobalStore } from '../stores/global';
import { useApiStore } from '../stores/api';
import { useUtilStore } from '../stores/util';
import { taskApi } from '../composables/useHttp';
import {
  Picker as VarPicker,
  Input as VarInput,
  Textarea as VarTextarea,
  DatePicker as VarDatePicker,
  Switch as VarSwitch,
  Button as VarButton,
  Dialog
} from '@varlet/ui';

export default {
  created() {
    const globalStore = useGlobalStore();
    globalStore.setMenu({
      menus: [],
      event: this
    });
    globalStore.setTitle(this.$t('app.task_add'));
    globalStore.setShowBack(true);
  },
  methods: {
    doAdd() {
      const globalStore = useGlobalStore();
      var jiacn = globalStore.getJiacn;
      taskApi.create('/create', {
        jiacn: jiacn,
        type: this.type[0],
        period: this.period[0],
        crond: this.crond,
        name: this.name,
        description: this.description,
        startTime: useUtilStore().toTimeStamp(new Date(this.start_time)),
        endTime: useUtilStore().toTimeStamp(new Date(this.end_time)),
        lunar: this.lunar,
        amount: this.amount,
        remind: this.remind
      }).then((res) => {
        if (res.code === 'E0') {
          Dialog({
            title: this.$t('app.notify'),
            message: res.msg,
            confirmButtonText: this.$t('app.confirm')
          });
          this.$router.go(-1);
        } else {
          Dialog({
            title: this.$t('app.alert'),
            message: res.msg,
            confirmButtonText: this.$t('app.confirm')
          });
        }
      });
    },
    changeType(val) {
      if (val[0] === '1') {
        this.amountShow = false;
      } else {
        this.amountShow = true;
      }
    },
    changePeriod(val) {
      if (val[0] === '6') {
        this.startTimeShow = true;
        this.endTimeShow = false;
        this.end_time = '';
      } else if (val[0] === '0') {
        this.startTimeShow = false;
        this.endTimeShow = false;
        this.start_time = '';
        this.end_time = '';
      } else {
        this.startTimeShow = true;
        this.endTimeShow = true;
      }
    }
  },
  data() {
    return {
      type: [],
      period: [],
      crond: '',
      name: '',
      description: '',
      start_time: '',
      startTimeShow: false,
      end_time: '',
      endTimeShow: false,
      amount: null,
      amountShow: false,
      remind: '1',
      lunar: '0',
      typeList: [
        {
          text: this.$t('task.type_notify'),
          value: '1'
        },
        {
          text: this.$t('task.type_target'),
          value: '2'
        },
        {
          text: this.$t('task.type_repayment'),
          value: '3'
        },
        {
          text: this.$t('task.type_fixed_income'),
          value: '4'
        }
      ],
      periodList: [
        {
          text: this.$t('task.period_long_term'),
          value: '0'
        },
        {
          text: this.$t('task.period_yearly'),
          value: '1'
        },
        {
          text: this.$t('task.period_monthly'),
          value: '2'
        },
        {
          text: this.$t('task.period_weekly'),
          value: '3'
        },
        {
          text: this.$t('task.period_daily'),
          value: '5'
        },
        {
          text: this.$t('task.period_hourly'),
          value: '11'
        },
        {
          text: this.$t('task.period_minutely'),
          value: '12'
        },
        {
          text: this.$t('task.period_secondly'),
          value: '13'
        },
        {
          text: this.$t('task.period_specific_date'),
          value: '6'
        }
      ]
    };
  }
};
</script>
<style scoped>
.picker-buttons {
  margin: 0 15px;
}
</style>
