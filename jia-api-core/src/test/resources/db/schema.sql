-- ----------------------------
-- Table structure for core_action
-- ----------------------------
DROP TABLE IF EXISTS core_action;
CREATE TABLE core_action  (
  id int NOT NULL AUTO_INCREMENT,
  resource_id varchar(50) NULL DEFAULT NULL,
  module varchar(30) NOT NULL,
  func varchar(50) NOT NULL,
  url varchar(100) NULL DEFAULT NULL,
  description varchar(50) NULL DEFAULT NULL,
  source int NULL DEFAULT 1,
  level int NULL DEFAULT 1,
  update_time bigint NULL DEFAULT NULL,
  create_time bigint NULL DEFAULT NULL,
  status int NOT NULL DEFAULT 1,
  PRIMARY KEY (id)
);

CREATE UNIQUE INDEX core_action_d_c_f ON core_action (module, func, resource_id);

-- ----------------------------
-- Table structure for core_dict
-- ----------------------------
DROP TABLE IF EXISTS core_dict;
CREATE TABLE core_dict  (
  id int NOT NULL AUTO_INCREMENT,
  type varchar(100) NULL DEFAULT NULL,
  language varchar(50) NULL DEFAULT NULL,
  name varchar(255) NULL DEFAULT NULL,
  value varchar(255) NULL DEFAULT NULL,
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
  id int NOT NULL AUTO_INCREMENT,
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