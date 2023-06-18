CREATE TABLE dwz_record (
  id int NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  orgi varchar(1000) DEFAULT NULL COMMENT '原地址',
  uri varchar(20) DEFAULT NULL COMMENT '目标地址',
  create_time bigint DEFAULT NULL COMMENT '记录时间',
  expire_time bigint DEFAULT NULL COMMENT '有效时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  pv int DEFAULT '0' COMMENT '访问量',
  PRIMARY KEY (id),
  UNIQUE KEY uri (uri)
) COMMENT='短网址记录';