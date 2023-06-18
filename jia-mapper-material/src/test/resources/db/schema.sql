CREATE TABLE mat_media (
  id int NOT NULL AUTO_INCREMENT COMMENT 'ID',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识码',
  title varchar(100) DEFAULT NULL COMMENT '标题',
  type int DEFAULT NULL COMMENT '类型 1图片 2语音 3视频 4缩略图 5富文本',
  url varchar(200) DEFAULT NULL COMMENT '路径',
  time bigint DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (id)
) COMMENT='媒体素材';

CREATE TABLE mat_news (
  id int NOT NULL AUTO_INCREMENT COMMENT 'ID',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识码',
  title varchar(100) DEFAULT NULL COMMENT '标题',
  author varchar(50) DEFAULT NULL COMMENT '作者',
  digest varchar(200) DEFAULT NULL COMMENT '摘要',
  bodyurl varchar(200) DEFAULT NULL COMMENT '正文链接',
  picurl varchar(200) DEFAULT NULL COMMENT '缩略图链接',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  PRIMARY KEY (id)
) COMMENT='图文素材';

CREATE TABLE mat_phrase (
  id int NOT NULL AUTO_INCREMENT COMMENT 'ID',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识码',
  content varchar(500) DEFAULT NULL COMMENT '内容',
  tag varchar(100) DEFAULT NULL COMMENT '标签',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  pv int DEFAULT '0' COMMENT '阅读量',
  up int DEFAULT '0' COMMENT '点赞量',
  down int DEFAULT '0' COMMENT '点踩量',
  jiacn varchar(32) DEFAULT NULL COMMENT '提交人JIA账号',
  PRIMARY KEY (id)
) COMMENT='短语表';

CREATE TABLE mat_phrase_vote (
  id int NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT '用户JIA账户',
  phrase_id int DEFAULT NULL COMMENT '短语表ID',
  vote int DEFAULT '0' COMMENT '投票情况 1赞 0踩',
  time bigint DEFAULT NULL COMMENT '时间',
  PRIMARY KEY (id),
  KEY phrase_vote_id (phrase_id),
  CONSTRAINT mat_phrase_vote_ibfk_1 FOREIGN KEY (phrase_id) REFERENCES mat_phrase (id) ON DELETE CASCADE
) COMMENT='短语投票情况表';

CREATE TABLE mat_pv_log (
  id int NOT NULL AUTO_INCREMENT,
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识码',
  resource_id varchar(50) DEFAULT NULL COMMENT '资源ID',
  entity_id int DEFAULT NULL COMMENT '实体ID',
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  phone varchar(20) DEFAULT NULL COMMENT '电话',
  email varchar(50) DEFAULT NULL COMMENT '邮箱',
  sex int DEFAULT '0' COMMENT '性别 1男性 2女性 0未知',
  nickname varchar(50) DEFAULT NULL COMMENT '昵称',
  avatar varchar(200) DEFAULT NULL COMMENT '头像',
  city varchar(50) DEFAULT NULL COMMENT '城市',
  country varchar(50) DEFAULT NULL COMMENT '国家',
  province varchar(50) DEFAULT NULL COMMENT '省份',
  latitude varchar(20) DEFAULT NULL COMMENT '纬度',
  longitude varchar(20) DEFAULT NULL COMMENT '经度',
  ip varchar(20) DEFAULT NULL COMMENT 'IP地址',
  uri varchar(200) DEFAULT NULL COMMENT '访问地址',
  user_agent varchar(500) DEFAULT NULL COMMENT '客户端信息',
  time bigint DEFAULT NULL COMMENT '记录时间',
  PRIMARY KEY (id)
) COMMENT='素材访问日志';

CREATE TABLE mat_tip (
  id int NOT NULL AUTO_INCREMENT COMMENT '打赏ID',
  type int DEFAULT NULL COMMENT '类型 1短语',
  entity_id int DEFAULT NULL COMMENT '业务实体ID',
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  price int DEFAULT NULL COMMENT '打赏金额（单位：分）',
  status int DEFAULT '0' COMMENT '状态 0未支付 1已支付 2已发货',
  time bigint DEFAULT NULL COMMENT '打赏时间',
  PRIMARY KEY (id)
) COMMENT='打赏情况表';

CREATE TABLE mat_vote (
  id int NOT NULL AUTO_INCREMENT,
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识码',
  name varchar(50) DEFAULT NULL COMMENT '投票名称',
  start_time bigint DEFAULT NULL COMMENT '发布时间',
  close_time bigint DEFAULT NULL COMMENT '截至时间',
  num int DEFAULT '0' COMMENT '投票人数',
  PRIMARY KEY (id)
) COMMENT='投票管理';

CREATE TABLE mat_vote_question (
  id int NOT NULL AUTO_INCREMENT,
  vote_id int DEFAULT NULL COMMENT '投票单ID',
  title varchar(200) DEFAULT NULL COMMENT '问题',
  multi int DEFAULT '0' COMMENT '是否多选 0否 1是',
  point int DEFAULT NULL COMMENT '分值',
  opt varchar(6) DEFAULT NULL COMMENT '实际答案',
  PRIMARY KEY (id),
  KEY question_vote_id (vote_id),
  CONSTRAINT mat_vote_question_ibfk_1 FOREIGN KEY (vote_id) REFERENCES mat_vote (id) ON DELETE CASCADE
) COMMENT='投票问题';

CREATE TABLE mat_vote_item (
  id int NOT NULL AUTO_INCREMENT,
  question_id int DEFAULT NULL COMMENT '问题ID',
  opt varchar(2) DEFAULT NULL COMMENT '选项',
  content varchar(200) DEFAULT NULL COMMENT '内容',
  tick int DEFAULT '0' COMMENT '是否正确 1是 0否',
  pic_url varchar(200) DEFAULT NULL COMMENT '图片地址',
  num int DEFAULT '0' COMMENT '票数',
  PRIMARY KEY (id),
  KEY vote_item_question_id (question_id),
  CONSTRAINT mat_vote_item_ibfk_1 FOREIGN KEY (question_id) REFERENCES mat_vote_question (id) ON DELETE CASCADE
) COMMENT='投票子项';

CREATE TABLE mat_vote_tick (
  id int NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT '用户JIA账户',
  vote_id int DEFAULT NULL COMMENT '投票ID',
  question_id int DEFAULT NULL COMMENT '问题ID',
  opt varchar(6) DEFAULT NULL COMMENT '选项',
  tick int DEFAULT '0' COMMENT '是否正确 1是 0否',
  time bigint DEFAULT NULL COMMENT '时间',
  PRIMARY KEY (id),
  KEY vote_tick_question_id (question_id),
  KEY tick_vote_id (vote_id),
  CONSTRAINT mat_vote_tick_ibfk_1 FOREIGN KEY (vote_id) REFERENCES mat_vote (id) ON DELETE CASCADE
) COMMENT='投票情况表';