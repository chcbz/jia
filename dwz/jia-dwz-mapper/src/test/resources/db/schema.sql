CREATE TABLE dwz_record (
  id bigint NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
  orig varchar(1000) DEFAULT NULL COMMENT '原地址',
  uri varchar(20) DEFAULT NULL COMMENT '目标地址',
  expire_time bigint DEFAULT NULL COMMENT '有效时间',
  status int DEFAULT '1' COMMENT '状态 1有效 0无效',
  pv int DEFAULT '0' COMMENT '访问量',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
  PRIMARY KEY (id),
  UNIQUE KEY uri (uri)
) COMMENT='短网址记录';