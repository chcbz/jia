INSERT INTO user_perms (id, module, func, url, description, source, level, status, create_time, update_time, client_id, tenant_id) VALUES (1, 'jia-oauth', 'action', 'update', 'action/update', 'hasAuthority(''action-update'')', 1, 1, 1, 1715516216763, 1737531392773, null, null);

INSERT INTO user_perms_rel (role_id, perms_id, update_time, create_time) VALUES (1, 1, 1560767075, 1560767075);

INSERT INTO user_group (id, client_id, name, code, remark, create_time, update_time, status)
VALUES (1, 'jianO8sbRLofO5XcBxL', '财务主管', 'Finance', '备注', 1553217865, 1553217865, 1);

INSERT INTO user_group_rel (user_id, group_id, update_time, create_time) VALUES (1, 1, 1553852197, 1553852197);

INSERT INTO user_info (id, username, password, openid, jiacn, phone, email, sex, nickname, avatar, city, country,
province, latitude, longitude, point, referrer, birthday, tel, weixin, qq, position, status, remark, msg_type,
subscribe, create_time, update_time) VALUES (1, 'oH2zD1El9hvjnWu-LRmCr-JiTuXI', '123', 'oH2zD1El9hvjnWu-LRmCr-JiTuXI',
'oH2zD1El9hvjnWu-LRmCr-JiTuXI', '13450909878', null, 1, '张三', 'avatar/20200621080105_oH2zD1El9hvjnWu-LRmCr-JiTuXI.jpg',
'东莞',
'中国', '广东', '22.950199', '113.891899', 165, null, null, null, null, null, '1', null, null, '1', 'wxd59557202ddff2d5',
1525668697, 1608162034);

INSERT INTO user_msg (id, title, content, url, type, user_id, status, create_time, update_time)
VALUES (185, '亲爱的UltraChen,您有新的待办任务，请及时处理。', '', 'http://task.test.net', '1', 1, 0, 1560826201, 1560826201);

INSERT INTO user_org (id, client_id, name, p_id, type, code, remark, role, director, logo, logo_icon, update_time,
create_time, status) VALUES (1, 'jia_client', 'org1', 0, 1, 'FLB', '', '1', '1', null, null, 1524538869, 1524538786, 1);
INSERT INTO user_org (id, client_id, name, p_id, type, code, remark, role, director, logo, logo_icon, update_time,
create_time, status) VALUES (2, 'jia_client', 'org2', 0, 1, 'CHC', '', '2', '2', null, null, 1524538869, 1524538786, 1);
INSERT INTO user_org (id, client_id, name, p_id, type, code, remark, role, director, logo, logo_icon, update_time,
create_time, status) VALUES (3, 'jia_client', 'org3', 1, 1, 'BYA', '', '3', '3', null, null, 1524538869, 1524538786, 1);

INSERT INTO user_org_rel (user_id, org_id, update_time, create_time) VALUES (1, 1, null, null);

INSERT INTO user_role (id, client_id, name, code, remark, update_time, create_time, status)
VALUES (1, null, '所有权限', 'AdministratorAccess', '该策略允许您管理账户内所有用户及其权限', 1553217909, 1521711133, 1);

INSERT INTO user_role_rel (user_id, group_id, role_id, client_id, update_time, create_time)
VALUES (1, null, 1, 'jia_client', 1521711133, 1521711133);
INSERT INTO user_role_rel (user_id, group_id, role_id, client_id, update_time, create_time)
VALUES (null, 1, 1, 'jia_client', 1521711133, 1521711133);
