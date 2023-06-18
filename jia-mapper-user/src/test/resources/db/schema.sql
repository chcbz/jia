CREATE TABLE user_auth (
  role_id int NOT NULL COMMENT '角色ID',
  perms_id int NOT NULL COMMENT '权限ID',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  UNIQUE KEY nid_rid (perms_id,role_id),
  KEY user_auth_role_id (role_id),
) COMMENT='角色与权限对应表';

CREATE TABLE user_group (
  id int NOT NULL AUTO_INCREMENT,
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  name varchar(100) DEFAULT NULL COMMENT '组名',
  code varchar(50) DEFAULT NULL COMMENT '编码',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  create_time bigint DEFAULT NULL COMMENT '创建日期',
  update_time bigint DEFAULT NULL COMMENT '最后更新日期',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  PRIMARY KEY (id)
) COMMENT='用户组';

CREATE TABLE user_group_rel (
  user_id int NOT NULL COMMENT '用户ID',
  group_id int NOT NULL COMMENT '组ID',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  KEY user_group_rel_user_id (user_id),
  KEY user_group_rel_group_id (group_id),
) COMMENT='用户组关联表';

CREATE TABLE user_info (
  id int NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  username varchar(32) DEFAULT NULL COMMENT '用户名',
  password varchar(32) DEFAULT '123' COMMENT '密码',
  openid varchar(32) DEFAULT NULL COMMENT '微信openid',
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  phone varchar(20) DEFAULT NULL COMMENT '电话',
  email varchar(50) DEFAULT NULL COMMENT '邮箱',
  sex int DEFAULT NULL COMMENT '性别 1男性 2女性 0未知',
  nickname varchar(50) DEFAULT NULL COMMENT '昵称',
  avatar varchar(200) DEFAULT NULL COMMENT '头像',
  city varchar(50) DEFAULT NULL COMMENT '城市',
  country varchar(50) DEFAULT NULL COMMENT '国家',
  province varchar(50) DEFAULT NULL COMMENT '省份',
  latitude varchar(20) DEFAULT NULL COMMENT '纬度',
  longitude varchar(20) DEFAULT NULL COMMENT '经度',
  point int DEFAULT '0' COMMENT '积分',
  referrer varchar(32) DEFAULT NULL COMMENT '推荐人Jia账号',
  birthday date DEFAULT NULL COMMENT '出生日期',
  tel varchar(20) DEFAULT NULL COMMENT '固定电话',
  weixin varchar(20) DEFAULT NULL COMMENT '微信号',
  qq varchar(20) DEFAULT NULL COMMENT 'QQ号',
  position varchar(255) DEFAULT NULL COMMENT '职位ID',
  status int DEFAULT NULL COMMENT '状态 1在岗 2出差 0离岗',
  remark varchar(200) DEFAULT NULL COMMENT '简短说明',
  msg_type varchar(10) DEFAULT '1' COMMENT '接收消息的方式（多个以逗号隔开） 1微信 2邮箱 3短信',
  subscribe varchar(500) DEFAULT 'vote' COMMENT '订阅内容（多个以逗号隔开）',
  create_time bigint DEFAULT NULL COMMENT '创建日期',
  update_time bigint DEFAULT NULL COMMENT '更新日期',
  PRIMARY KEY (id),
  UNIQUE KEY user_info_jiacn (jiacn),
  UNIQUE KEY user_info_openid (openid)
) COMMENT='用户信息';

CREATE TABLE user_msg (
  id int NOT NULL AUTO_INCREMENT,
  title varchar(100) DEFAULT NULL COMMENT '标题',
  content varchar(2000) DEFAULT NULL COMMENT '内容',
  url varchar(200) DEFAULT NULL COMMENT '地址',
  type varchar(20) DEFAULT NULL COMMENT '类型',
  user_id int DEFAULT NULL COMMENT '用户ID',
  status int DEFAULT '1' COMMENT '状态 1未读 2已读 0已删除',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  PRIMARY KEY (id)
) COMMENT='消息内容';

CREATE TABLE user_org (
  id int NOT NULL AUTO_INCREMENT COMMENT '组织ID',
  client_id varchar(50) DEFAULT NULL,
  name varchar(100) DEFAULT NULL COMMENT '组织名称',
  p_id int DEFAULT NULL COMMENT '夫组织ID',
  type int DEFAULT NULL COMMENT '机构类型 1公司 2子公司 3总监 4经理 5职员 6客户',
  code varchar(50) DEFAULT NULL COMMENT '机构编码',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  director varchar(50) DEFAULT NULL COMMENT '负责人',
  logo varchar(200) DEFAULT NULL,
  logo_icon varchar(200) DEFAULT NULL,
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  status int DEFAULT NULL COMMENT '状态(1:正常,0:停用)',
  PRIMARY KEY (id)
) COMMENT='组织表';

CREATE TABLE user_org_rel (
  user_id int NOT NULL COMMENT '用户ID',
  org_id int NOT NULL COMMENT '组织ID',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  KEY user_org_rel_user_id (user_id),
  KEY user_org_rel_org_id (org_id),
) COMMENT='用户角色关联表';

CREATE TABLE user_role (
  id int NOT NULL AUTO_INCREMENT,
  client_id varchar(50) DEFAULT NULL,
  name varchar(25) NOT NULL COMMENT '角色名',
  code varchar(20) DEFAULT NULL COMMENT '编码',
  remark varchar(200) DEFAULT NULL COMMENT '备注',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  status int NOT NULL DEFAULT '1' COMMENT '状态(1:正常,0停用)',
  PRIMARY KEY (id),
  UNIQUE KEY user_role_rolename (name)
) COMMENT='角色表';

CREATE TABLE user_role_rel (
  user_id int DEFAULT NULL COMMENT '用户ID',
  group_id int DEFAULT NULL COMMENT '用户组ID',
  role_id int NOT NULL COMMENT '角色ID',
  client_id varchar(50) DEFAULT NULL,
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  KEY user_role_rel_user_id (user_id),
  KEY user_role_rel_role_id (role_id),
  KEY user_role_rel_group_id (group_id),
) COMMENT='用户角色关联表';