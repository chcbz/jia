CREATE TABLE point_gift (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '礼品ID',
  name varchar(100) DEFAULT NULL COMMENT '礼品名称',
  description varchar(1000) DEFAULT NULL COMMENT '礼品描述',
  pic_url varchar(200) DEFAULT NULL COMMENT '礼品图片地址',
  point int DEFAULT NULL COMMENT '礼品所需积分',
  price int DEFAULT NULL COMMENT '价格（单位：分）',
  quantity int DEFAULT NULL COMMENT '礼品数量',
  virtual_flag int DEFAULT '0' COMMENT '是否虚拟物品 0否 1是',
  status int DEFAULT '1' COMMENT '状态 1上架 0下架',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='礼品信息';

CREATE TABLE point_gift_usage (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '礼品兑换ID',
  gift_id bigint DEFAULT NULL COMMENT '礼品ID',
  name varchar(100) DEFAULT NULL COMMENT '礼品名称',
  description varchar(1000) DEFAULT NULL COMMENT '礼品描述',
  pic_url varchar(200) DEFAULT NULL COMMENT '图片地址',
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  quantity int DEFAULT NULL COMMENT '兑换数量',
  point int DEFAULT NULL COMMENT '所需积分',
  price int DEFAULT NULL COMMENT '消费金额（单位：分）',
  consignee varchar(50) DEFAULT NULL COMMENT '收货人',
  phone varchar(20) DEFAULT NULL COMMENT '收货电话',
  address varchar(200) DEFAULT NULL COMMENT '收货地址',
  card_no varchar(50) DEFAULT NULL COMMENT '虚拟卡号',
  status int DEFAULT '0' COMMENT '状态 0未支付 1已支付 2已发货 3已收货 4已完成 5已取消',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='礼品兑换情况表';

CREATE TABLE point_record (
  id bigint NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  type int DEFAULT NULL COMMENT '积分类型 1新用户 2签到 3推荐 4礼品兑换 5试试手气 6答题 7短语被赞',
  chg int DEFAULT NULL COMMENT '积分变化',
  remain int DEFAULT NULL COMMENT '剩余积分',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='积分记录';

CREATE TABLE point_referral (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '推荐ID',
  referrer varchar(32) DEFAULT NULL COMMENT '推荐人Jia账号',
  referral varchar(32) DEFAULT NULL COMMENT '被推荐人Jia账号',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='推荐信息';

CREATE TABLE point_sign (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '签到ID',
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  address varchar(200) DEFAULT NULL COMMENT '地点',
  latitude varchar(20) DEFAULT NULL COMMENT '纬度',
  longitude varchar(20) DEFAULT NULL COMMENT '经度',
  point int DEFAULT NULL COMMENT '当此得分',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='签到信息';