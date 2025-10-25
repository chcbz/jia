-- ----------------------------
-- Table structure for core_dict
-- ----------------------------
DROP TABLE IF EXISTS core_dict;
CREATE TABLE core_dict  (
  id bigint NOT NULL AUTO_INCREMENT,
  type varchar(100) NULL DEFAULT NULL,
  language varchar(50) NULL DEFAULT NULL,
  name varchar(255) NULL DEFAULT NULL,
  `value` varchar(255) NULL DEFAULT NULL,
  url varchar(100) NULL DEFAULT NULL,
  parent_id varchar(32) NULL DEFAULT NULL,
  dict_order int NULL DEFAULT NULL,
  description varchar(5000) NULL DEFAULT NULL,
  update_time bigint NULL DEFAULT NULL,
  create_time bigint NULL DEFAULT NULL,
  status tinyint(1) NULL DEFAULT 1,
  PRIMARY KEY (id)
);

-- ----------------------------
-- Table structure for core_log
-- ----------------------------
DROP TABLE IF EXISTS core_log;
CREATE TABLE core_log  (
  id bigint NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) NULL DEFAULT NULL,
  username varchar(50) NULL DEFAULT NULL,
  ip varchar(20) NULL DEFAULT NULL,
  uri varchar(100) NULL DEFAULT NULL,
  method varchar(10) NULL DEFAULT NULL,
  param text NULL,
  user_agent varchar(500) NULL DEFAULT NULL,
  header varchar(5000) NULL DEFAULT NULL,
  time bigint NULL DEFAULT NULL,
  PRIMARY KEY (id)
);