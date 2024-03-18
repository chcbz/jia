CREATE TABLE sms_buy (
  id bigint NOT NULL AUTO_INCREMENT,
  number int DEFAULT NULL COMMENT '充值数量',
  money decimal(7,2) DEFAULT NULL COMMENT '充值金额',
  total int DEFAULT NULL COMMENT '历史充值数量',
  remain int DEFAULT NULL COMMENT '剩余数量',
  status int DEFAULT '0' COMMENT '状态 0未支付 1已支付 2已取消',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='短信充值情况';

CREATE TABLE sms_code (
  id bigint NOT NULL AUTO_INCREMENT,
  phone varchar(30) DEFAULT NULL COMMENT '手机号码',
  sms_code varchar(6) DEFAULT NULL COMMENT '短信验证码',
  sms_type int DEFAULT NULL COMMENT '短信类型  1登录 2忘记密码',
  count int DEFAULT '1' COMMENT '发送次数',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='手机短信表';

CREATE INDEX sms_code_phone_sms_type_status_idx ON sms_code (phone, sms_type, status);
CREATE INDEX sms_code_create_time_idx ON sms_code (create_time);

CREATE TABLE sms_config (
  client_id varchar(50) NOT NULL COMMENT '应用标识符',
  short_name varchar(10) DEFAULT NULL COMMENT '简称',
  reply_url varchar(200) DEFAULT NULL COMMENT '短信回复回调地址',
  remain int DEFAULT '0' COMMENT '剩余可用数量',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (client_id)
) COMMENT='客户端配置表';

CREATE TABLE sms_message (
  id bigint NOT NULL AUTO_INCREMENT,
  template_id varchar(50) DEFAULT NULL COMMENT '模板ID',
  sender varchar(50) DEFAULT NULL COMMENT '发送者',
  receiver varchar(50) DEFAULT NULL COMMENT '接收者',
  title varchar(100) DEFAULT NULL COMMENT '标题',
  content varchar(500) DEFAULT NULL COMMENT '内容',
  url varchar(200) DEFAULT NULL COMMENT '链接地址',
  msg_type int DEFAULT NULL COMMENT '消息类型 1微信 2邮件 3短息',
  status int DEFAULT '0' COMMENT '状态 0未发送 1已发送',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='消息发送';

CREATE TABLE sms_package (
  id bigint NOT NULL AUTO_INCREMENT,
  number int DEFAULT NULL COMMENT '数量',
  money decimal(7,2) DEFAULT NULL COMMENT '金额',
  `order` int DEFAULT NULL COMMENT '序列',
  status int DEFAULT NULL COMMENT '状态 1有效 0无效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='短信套餐';

CREATE TABLE sms_reply (
  id bigint NOT NULL AUTO_INCREMENT,
  msgid varchar(30) DEFAULT NULL,
  mobile varchar(20) DEFAULT NULL,
  xh varchar(10) DEFAULT NULL,
  content varchar(500) DEFAULT NULL,
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='短信回复情况表';

CREATE TABLE sms_send (
  mobile varchar(20) DEFAULT NULL COMMENT '手机号码',
  content varchar(500) DEFAULT NULL COMMENT '短信内容',
  xh varchar(10) DEFAULT NULL COMMENT '小号',
  msgid varchar(30) NOT NULL COMMENT '消息编号',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (msgid)
) COMMENT='短信发送情况表';

CREATE TABLE sms_template (
  template_id varchar(32) NOT NULL COMMENT '模板ID',
  name varchar(50) DEFAULT NULL COMMENT '模板名称',
  title varchar(500) DEFAULT NULL COMMENT '模板标题',
  content varchar(5000) DEFAULT NULL COMMENT '模板内容',
  msg_type int DEFAULT NULL COMMENT '消息类型 1微信 2邮件 3短信',
  type int DEFAULT NULL COMMENT '模板类型',
  status int DEFAULT '0' COMMENT '状态 0待审核 1审核通过 2审核失败',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (template_id)
) COMMENT='短信模板';