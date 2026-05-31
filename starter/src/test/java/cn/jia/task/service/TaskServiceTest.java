package cn.jia.task.service;

import cn.jia.core.util.DateUtil;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.test.BaseDbUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Calendar;

public class TaskServiceTest extends BaseDbUnitTest {

    @Autowired
    private TaskService taskService;

    @Test
    void create() {
        String[] names = "丁威,付春花,龚祖尧,胡家乐,蒋红霞,廖慧,晋改平,金可华,黎龙,林命才,李琼,刘小明,罗俏英,彭宗乙,危春红,吴玉凤,吴智情,肖冬琦,肖冬燕,叶卫青,叶亚银,袁琪,张康群,邹梅清,邹文晴,邹志甫".split(",");
        String[] sexs = "先生,女士,先生,先生,女士,女士,女士,先生,先生,先生,女士,女士,女士,先生,女士,女士,先生,女士,女士,先生,先生,女士,女士,女士,先生,先生".split(",");
        String[] ages = "36,50,46,5,46,31,36,43,31,52,58,51,50,23,43,47,43,27,29,47,77,63,33,44,30,52".split(",");
        String[] births = "1984/4/26,1970/5/21,1973/12/27,2015/2/7,1973/10/9,1989/5/21,1984/4/17,1976/12/25,1989/2/10,1968/1/8,1961/7/24,1968/7/16,1970/5/1,1996/9/22,1977/2/5,1973/6/3,1976/9/28,1992/12/27,1991/1/31,1973/2/3,1943/3/22,1956/8/27,1987/1/24,1975/9/12,1989/8/29,1967/11/12".split(",");
        String[] phones = "13612818816,13632627934,13728628693,15112547017,18923769250,15112547017,13528426367,13728927744,13823733523,13927415670,15014073453,13602516516,13533862374,18789084625,13622379272,13417401806,13724374758,13670323707,13927415670,13360099138,18922833633,13612818816,13714078329,13544080352,15622814560,13544085016".split(",");
        for(int i=0;i<names.length;i++) {
            TaskPlanEntity task = new TaskPlanEntity();
            task.setJiacn("oH2zD1PUPvspicVak69uB4wDaFLg");
            task.setName(names[i] + sexs[i] + "生日");
            task.setDescription("手机号码是" + phones[i] + "，请送上诚挚的问候。");
            task.setRemind(TaskConstants.TASK_REMIND_YES);
            task.setRemindPhone(phones[i]);
            task.setRemindMsg("友谊不问名利但求诚心，朋友不问距离但求相应。人的一生消逝的是时间，收获的是朋友，祝您生日快乐！");
            task.setType(TaskConstants.TASK_TYPE_NOTIFY);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(DateUtil.parseDate(births[i], "yyyy/MM/dd"));
            task.setStartTime(DateUtil.genTime(calendar.getTime()));
            calendar.add(Calendar.YEAR, Integer.parseInt(ages[i]) + 10);
            task.setEndTime(DateUtil.genTime(calendar.getTime()));
            task.setLunar(TaskConstants.COMMON_ENABLE);
            task.setPeriod(TaskConstants.TASK_REMIND_YES);
            taskService.create(task);
        }
    }
}