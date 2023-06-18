CREATE TABLE task_plan (
  id int NOT NULL AUTO_INCREMENT,
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  jiacn varchar(32) NOT NULL COMMENT 'JIA账号',
  type int NOT NULL COMMENT '任务类型 1常规提醒 2目标 3还款计划 4固定收入',
  period int NOT NULL DEFAULT '0' COMMENT '周期类型 0长期 1每年 2每月 3每周 5每日 11每小时 12每分钟 13每秒 6指定日期',
  crond varchar(20) DEFAULT NULL COMMENT '周期表达式',
  name varchar(30) NOT NULL COMMENT '任务名称',
  description varchar(200) DEFAULT NULL COMMENT '任务描述',
  lunar int DEFAULT '0' COMMENT '是否农历日期 1是 0否',
  start_time bigint DEFAULT NULL COMMENT '开始时间',
  end_time bigint DEFAULT NULL COMMENT '结束时间',
  amount decimal(10,2) DEFAULT NULL COMMENT '数量/金额',
  remind int NOT NULL DEFAULT '0' COMMENT '是否需要提醒 1是 0否',
  remind_phone varchar(20) DEFAULT NULL COMMENT '提醒手机号码',
  remind_msg varchar(200) DEFAULT NULL COMMENT '提醒信息',
  status int NOT NULL DEFAULT '1' COMMENT '状态 1有效 0无效',
  create_time bigint NOT NULL COMMENT '创建日期',
  update_time bigint NOT NULL COMMENT '最后更新日期',
  PRIMARY KEY (id)
) COMMENT='任务计划';

CREATE TABLE task_item (
  id int NOT NULL AUTO_INCREMENT,
  plan_id int DEFAULT NULL COMMENT '计划ID',
  time bigint DEFAULT NULL COMMENT '时间',
  status int DEFAULT '1' COMMENT '状态 1正常 0已失效',
  PRIMARY KEY (id),
  KEY plan_id (plan_id),
  CONSTRAINT task_item_ibfk_1 FOREIGN KEY (plan_id) REFERENCES task_plan (id) ON DELETE CASCADE
) COMMENT='任务执行明细';

CREATE VIEW v_task_item AS select i.id AS id,i.plan_id AS plan_id,p.client_id AS client_id,p.jiacn AS jiacn,p.type AS type,p.period AS period,p.crond AS crond,p.name AS name,p.description AS description,p.amount AS amount,p.remind AS remind,p.remind_phone AS remind_phone,p.remind_msg AS remind_msg,p.status AS status,i.time AS time from (task_plan p join task_item i on((p.id = i.plan_id))) order by i.time;