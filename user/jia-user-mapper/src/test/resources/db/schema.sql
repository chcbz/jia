CREATE TABLE user_info (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username varchar(32) DEFAULT NULL COMMENT '用户名',
    password varchar(64) DEFAULT '123' COMMENT '密码',
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
    create_time bigint DEFAULT NULL COMMENT '创建时间',
    update_time bigint DEFAULT NULL COMMENT '最后更新时间',
    client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY user_info_jiacn_idx (jiacn),
    KEY user_info_openid_idx (openid),
    KEY user_info_username_idx (username),
    KEY user_info_phone_idx (phone)
) COMMENT='用户信息';

CREATE TABLE user_group (
  id bigint NOT NULL AUTO_INCREMENT,
  name varchar(100) DEFAULT NULL COMMENT '组名',
  code varchar(50) DEFAULT NULL COMMENT '编码',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='用户组';

CREATE TABLE user_group_rel (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL COMMENT '用户ID',
  group_id bigint NOT NULL COMMENT '组ID',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY user_group_rel_user_id_idx (user_id),
  KEY user_group_rel_group_id_idx (group_id)
) COMMENT='用户组关联表';

CREATE TABLE user_msg (
  id bigint NOT NULL AUTO_INCREMENT,
  title varchar(100) DEFAULT NULL COMMENT '标题',
  content varchar(2000) DEFAULT NULL COMMENT '内容',
  url varchar(200) DEFAULT NULL COMMENT '地址',
  type varchar(20) DEFAULT NULL COMMENT '类型',
  user_id bigint DEFAULT NULL COMMENT '用户ID',
  status int DEFAULT '1' COMMENT '状态 1未读 2已读 0已删除',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id)
) COMMENT='消息内容';

CREATE TABLE user_org (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '组织ID',
  name varchar(100) DEFAULT NULL COMMENT '组织名称',
  p_id bigint DEFAULT NULL COMMENT '父组织ID',
  type int DEFAULT NULL COMMENT '机构类型 1公司 2子公司 3总监 4经理 5职员 6客户',
  code varchar(50) DEFAULT NULL COMMENT '机构编码',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  role varchar(50) DEFAULT NULL COMMENT '角色',
  director varchar(50) DEFAULT NULL COMMENT '负责人',
  logo varchar(200) DEFAULT NULL,
  logo_icon varchar(200) DEFAULT NULL,
  status int DEFAULT NULL COMMENT '状态(1:正常,0:停用)',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY user_org_p_id_idx (p_id)
) COMMENT='组织表';

CREATE TABLE user_org_rel (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL COMMENT '用户ID',
  org_id bigint NOT NULL COMMENT '组织ID',
  status int DEFAULT '1' COMMENT '状态(1:正常,0:停用)',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY user_org_rel_user_id_idx (user_id),
  KEY user_org_rel_org_id_idx (org_id)
) COMMENT='用户角色关联表';

CREATE TABLE user_role (
  id bigint NOT NULL AUTO_INCREMENT,
  name varchar(25) NOT NULL COMMENT '角色名',
  code varchar(20) DEFAULT NULL COMMENT '编码',
  remark varchar(200) DEFAULT NULL COMMENT '备注',
  status int NOT NULL DEFAULT '1' COMMENT '状态(1:正常,0停用)',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY user_role_name_idx (name)
) COMMENT='角色表';

CREATE TABLE user_role_rel (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint DEFAULT NULL COMMENT '用户ID',
  group_id bigint DEFAULT NULL COMMENT '用户组ID',
  role_id bigint NOT NULL COMMENT '角色ID',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY user_role_rel_user_id_idx (user_id),
  KEY user_role_rel_role_id_idx (role_id),
  KEY user_role_rel_group_id_idx (group_id)
) COMMENT='用户角色关联表';

CREATE TABLE user_perms (
    id bigint NOT NULL AUTO_INCREMENT,
    module varchar(30) NOT NULL COMMENT '模块',
    func varchar(50) NOT NULL COMMENT '方法',
    url varchar(100) DEFAULT NULL COMMENT '服务地址',
    description varchar(50) DEFAULT NULL COMMENT '备注',
    source int DEFAULT '1' COMMENT '数据来源 1系统生成 2手动创建',
    level int DEFAULT '1' COMMENT '权限级别 1总后台 2企业后台',
    status int NOT NULL DEFAULT '1' COMMENT '状态(1:正常,0:停用)',
    create_time bigint DEFAULT NULL COMMENT '创建时间',
    update_time bigint DEFAULT NULL COMMENT '最后更新时间',
    client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    UNIQUE KEY d_c_f (module,func,client_id,tenant_id) USING BTREE
) COMMENT='资源表';

CREATE TABLE user_perms_rel (
    id bigint NOT NULL AUTO_INCREMENT,
    role_id bigint NOT NULL COMMENT '角色ID',
    perms_id bigint NOT NULL COMMENT '权限ID',
    expire_time bigint DEFAULT NULL COMMENT '过期时间',
    status int DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time bigint DEFAULT NULL COMMENT '创建时间',
    update_time bigint DEFAULT NULL COMMENT '最后更新时间',
    client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY user_perms_rel_role_id_idx (role_id),
    KEY user_perms_rel_perms_id_idx (perms_id)
) COMMENT='角色与权限对应表';