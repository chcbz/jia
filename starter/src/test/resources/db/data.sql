INSERT INTO core_dict (id, type, `language`, name, `value`, url, parent_id, dict_order, description, update_time,
                       create_time, status)
VALUES (1, 'ERROR_CODE', 'zh_CN', '系统未知错误,请联系管理员!', 'E999', '', '', 1, '系统未知错误,请联系管理员!',
        1596355962, 1524821391, 1);
INSERT INTO core_dict (id, type, `language`, name, `value`, url, parent_id, dict_order, description, update_time,
                       create_time, status)
VALUES (2, 'ERROR_CODE', 'zh_CN', '用户积分不够！', 'EUSER001', '', '', 1, '用户积分不够！', 1524821391, 1524821391, 1);
INSERT INTO core_dict (id, type, `language`, name, `value`, url, parent_id, dict_order, description, update_time,
                       create_time, status)
VALUES (3, 'ERROR_CODE', 'zh_CN', '用户不存在！', 'EUSER002', '', '', 1, '用户不存在！', 1524821391, 1524821391, 1);
INSERT INTO core_dict (id, type, `language`, name, `value`, url, parent_id, dict_order, description, update_time,
                       create_time, status)
VALUES (4, 'ERROR_CODE', 'zh_CN', '礼品不存在！', 'EPOINT001', '', '', 1, '礼品不存在！', 1524821391, 1524821391, 1);
INSERT INTO core_dict (id, type, `language`, name, `value`, url, parent_id, dict_order, description, update_time,
                       create_time, status)
VALUES (5, 'ERROR_CODE', 'zh_CN', '礼品数量不足！', 'EPOINT002', '', '', 1, '礼品数量不足！', 1524821391, 1524821391, 1);
INSERT INTO core_dict (id, type, `language`, name, `value`, url, parent_id, dict_order, description, update_time,
                       create_time, status)
VALUES (6, 'ERROR_CODE', 'zh_CN', '签到时间还没到！', 'EPOINT003', '', '', 1, '签到时间还没到！', 1524821391, 1524821391,
        1);
INSERT INTO core_dict (id, type, `language`, name, `value`, url, parent_id, dict_order, description, update_time,
                       create_time, status)
VALUES (7, 'ERROR_CODE', 'zh_CN', '该用户已经被其他人推荐过！', 'EPOINT004', '', '', 1, '该用户已经被其他人推荐过！',
        1524821391, 1524821391, 1);

INSERT INTO dwz_record (id, jiacn, orig, uri, create_time, expire_time, update_time, status, pv)
VALUES (1, '',
        'https://open.weixin.qq.com/connect/oauth2/authorize?appId=wxe752b0137f625797&redirect_uri=https%3A%2F%2Fweixin.e-chinalife.com%2Fmic%2F%2FcommBusi%2Fo2redt.action%3Fk%3Dgh_b47c5b804348%26p11%3D0%26linkUrl%3Dhttp%253A%252F%252Fgroup.e-chinalife.com%252Fecs-online%252Fweb%252Fsale%252Fnewcarsale%252Fxintoubao_login.html%253Fp1%253D1%2526p2%253D2%2526p3%253D%257B%257BopenId%257D%257D%2526p5%253D%257B%257BseriNo%257D%257D%2526p35%253DPA44023131008234%2526p36%253D%25E9%2599%2588%25E6%2583%25A0%25E8%25B6%2585%2526p37%253D13450070072%2523%255B%255BjsSignature%255D%255D&response_type=code&scope=snsapi_base&state=625797#wechat_redirect',
        '8cSLjQD3', 1572501952, 1683601952, 1572507566, 1, 9);
INSERT INTO dwz_record (id, jiacn, orig, uri, create_time, expire_time, update_time, status, pv)
VALUES (2, null,
        'https://es.dmbcdn.com/m/product-mix/bdfcd4bd-c74f-482c-a4d2-3faaf8c7d25a/intro?workid=PA44023131008234&tel=18666460519&name=%E9%99%88%E6%83%A0%E8%B6%85&company=%E4%B8%AD%E5%9B%BD%E4%BA%BA%E5%AF%BF',
        'FdeHq4BU', 1572501952, 1683601952, 1572504403, 1, 0);

INSERT INTO isp_file (id, client_id, name, uri, size, type, extension, status, create_time, update_time)
VALUES (1, 'jia_client', 'oH2zD1IyhO9QU415JiBVCrrBcnlg.jpg', 'avatar/20191204160906_oH2zD1IyhO9QU415JiBVCrrBcnlg.jpg',
        2874, 2, 'jpg', 1, 1575446946, 1575446946);

INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, parent_type, type_category, wx_template_id, wx_template,
                           wx_template_txt, sms_template_id, sms_template, url, status, create_time, update_time)
VALUES (1, 'jia_client', 'vote', '每日一题', null, 'wxmp', 'M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU',
        '[{"name":"first","value":"","color":"#173177"},{"name":"keyword1","value":"每天答题时间(两小时有效)","color":"#173177"},{"name":"keyword2","value":"#0#\\n\\n#1#\\n\\n请回复正确答案，退订回复TD","color":"#173177"},{"name":"remark","value":"","color":"#173177"}]',
        '每天答题时间(两小时有效)\\n#0#\\n\\n#1#\\n\\n请回复正确答案，退订回复TD', '', null, null, 1, 1584175547,
        1584177170);

INSERT INTO mat_media (id, client_id, title, type, url, create_time)
VALUES (1, 'jia_client', '长按二维码关注图.png', 1,
        'image/1526478392_长按二维码关注图.png', 1526478392);

INSERT INTO mat_phrase (id, client_id, content, tag, create_time, update_time, status, pv, up, down, jiacn)
VALUES (1, 'jia_client', '你以为有钱人很快乐吗?他们的快乐你根本想象不到！', '毒鸡汤', 1576631673, 1576631673, 1, 0, 0, 0,
        'oH2zD1PUPvspicVak69uB4wDaFLg');
INSERT INTO mat_phrase (id, client_id, content, tag, create_time, update_time, status, pv, up, down, jiacn)
VALUES (2, 'jia_client', '时间真的能改变一个人，比如你以前丑，现在更丑。', '毒鸡汤', 1576631673, 1578274619, 1, 1, 0, 0,
        'oH2zD1PUPvspicVak69uB4wDaFLg');

INSERT INTO mat_phrase_vote (id, jiacn, phrase_id, vote, create_time)
VALUES (1, 'oH2zD1PUPvspicVak69uB4wDaFLg', 1, 1,
        1577797034);

INSERT INTO mat_vote (id, client_id, name, start_time, close_time, num)
VALUES (1, 'jia_client', '保险基础知识考试试卷', 1572683060, 1604219060, 1121);
INSERT INTO mat_vote_question (id, vote_id, title, multi, point, opt)
VALUES (1, 1, '企业、家庭或个人面临的和潜在的风险加以判断、归类和对风险性质进行鉴定的过程是（      ）。', 0, 1, 'C');
INSERT INTO mat_vote_item (id, question_id, opt, content, tick, pic_url, num)
VALUES (1, 1, 'A', '风险评价', 0, null, 8);
INSERT INTO mat_vote_item (id, question_id, opt, content, tick, pic_url, num)
VALUES (2, 1, 'B', '风险估测', 0, null, 9);
INSERT INTO mat_vote_item (id, question_id, opt, content, tick, pic_url, num)
VALUES (3, 1, 'C', '风险识别', 1, null, 28);

insert into oauth_client (id, client_id, client_id_issued_at, client_secret, client_secret_expires_at,
                                  client_name, client_authentication_methods, authorization_grant_types, redirect_uris,
                                  post_logout_redirect_uris, scopes, client_settings, token_settings, create_time,
                                  update_time, tenant_id, appcn)
values (1, 'jia_client', '2024-5-24', 'secret', '2034-5-24', 'jia客户端', 'client_secret_basic',
        'authorization_code,refresh_token,client_credentials', 'authorized', '', 'openid', '', '', '1716541603993',
        '1716541603993', '', '');

INSERT INTO point_gift (id, client_id, name, description, pic_url, point, price, quantity, virtual_flag, status,
                        create_time, update_time)
VALUES (1, null, '精美耳机', '精美耳机', 'https://mat.chaoyoufan.net/image/erji.jpg', 20, 2000, 69, 0, 0, 1523860845,
        1646872062);

INSERT INTO point_gift_usage (id, client_id, gift_id, name, description, pic_url, jiacn, quantity, point, price,
                              consignee, phone, address, card_no, status, create_time, update_time)
VALUES (1, null, 7, '伪/化物语 忍野忍 小忍 手办',
        '西尾维新《物语系列》轻小说及其改编动画中女主角。人类时期本名雅赛萝拉，约600年前，她是一个贵族出身的女孩，拥有着银色的长发，异色瞳，被称为美丽公主，她的美貌令任何人都不惜献上生命，她所走过的道路成为了尸山。在598
        年前被吸血鬼尊主苏伊赛德变成吸血鬼，并被苏伊赛德赋予了“姬丝秀忒·雅赛劳拉莉昂·刃下心”的名字，有“铁血的热血的冷血的吸血鬼”、“怪异中的怪异”、“怪异杀手”、“怪异之王”之称。现在则是外表看上去只有八岁，有着白皙肌肤、金色瞳孔的金发幼女。失去了身为吸血鬼的几乎所有力量，甚至失去了自己的名字。黄金周中由于封印障猫的功绩，而被忍野咩咩取名“忍野忍”，《伪物语》中被封印在历的影子里，与历缔结羁绊。',
        'https://mat.chaoyoufan.net/image/xiaorenshouban.jpg', 'oH2zD1PUPvspicVak69uB4wDaFLg', 1, 40, 0, '陈', '13423',
        '广东省', null, 1, 1576033780, null);

INSERT INTO point_referral (id, referrer, referral, create_time, update_time)
VALUES (4, 'o-q51v0NKHWrGwyPMu9J5xdTTG7E', 'o-q51v7nN6nXQ-ciwOiRFRuef-_Q', 1526649285, null);

INSERT INTO sms_buy (id, client_id, number, money, total, remain, create_time, status)
VALUES (1,
        'jianO8sbRLofO5XcBxL', 1000, 0.01, 1000, 1000, 1553692841, 1);

INSERT INTO sms_config (client_id, short_name, reply_url, remain)
VALUES ('jia_client', 'e2tt', 'https://apia.jia.wydiy.com/sms/reply', 6962);

INSERT INTO sms_send (client_id, mobile, content, xh, msgid, create_time)
VALUES ('jianO8sbRLofO5XcBxL', '13827228333',
        '【鸿燕】您的短信验证码是7648，若非本人发送，请忽略此短信。', null, '157528221364197838080', 1575282207);
INSERT INTO sms_send (client_id, mobile, content, xh, msgid, create_time)
VALUES ('jianO8sbRLofO5XcBxL', '13827228333',
        '【鸿燕】您的短信验证码是0383，若非本人发送，请忽略此短信。', null, '157534380952143705600', 1575343803);
INSERT INTO sms_send (client_id, mobile, content, xh, msgid, create_time)
VALUES ('jia_client', '18666460519',
        '【Jia顺】您的短信验证码是2156，若非本人发送，请忽略此短信。', null, '157545364947918904832', 1575453643);
INSERT INTO sms_send (client_id, mobile, content, xh, msgid, create_time)
VALUES ('jiayavq21s43cwcjyd', '13538788751',
        '【E2TT】您的短信验证码是9487，若非本人发送，请忽略此短信。', null, '157484053864593198080', 1574840533);
INSERT INTO sms_send (client_id, mobile, content, xh, msgid, create_time)
VALUES ('jiayavq21s43cwcjyd', '13538788751',
        '【E2TT】您的短信验证码是3857，若非本人发送，请忽略此短信。', null, '157484062324631888128', 1574840617);
INSERT INTO sms_send (client_id, mobile, content, xh, msgid, create_time)
VALUES ('jiayavq21s43cwcjyd', '13538788751',
        '【E2TT】您的短信验证码是6790，若非本人发送，请忽略此短信。', null, '157484069156794554368', 1574840686);

INSERT INTO task_plan (id, client_id, jiacn, type, period, crond, name, description, lunar, start_time, end_time,
                       amount, remind, remind_phone, remind_msg, status, create_time, update_time)
VALUES (20, 'jiafewnnv58ec2379c', 'oH2zD1PUPvspicVak69uB4wDaFLg', 3, 2, '0 0 8 10 * ?', '住房公积金', '', 0, 1533830400,
        1575936000, 4000.00, 0, null, null, 1, 1533542258, 1533542258);
INSERT INTO task_plan (id, client_id, jiacn, type, period, crond, name, description, lunar, start_time, end_time,
                       amount, remind, remind_phone, remind_msg, status, create_time, update_time)
VALUES (21, 'jiafewnnv58ec2379c', 'oH2zD1PUPvspicVak69uB4wDaFLg', 3, 2, '0 0 8 10 * ?', '家庭基础生活费', '', 0,
        1533830400, 1575936000, 3000.00, 0, null, null, 1, 1533542329, 1533542329);

INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (330, 20, 1533830400, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (331, 20, 1536508800, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (332, 20, 1539100800, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (333, 20, 1541779200, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (334, 20, 1544371200, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (335, 20, 1547049600, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (336, 20, 1549728000, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (337, 20, 1552147200, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (338, 20, 1554825600, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (339, 20, 1557417600, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (340, 20, 1560096000, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (341, 20, 1562688000, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (342, 20, 1565366400, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (343, 20, 1568044800, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (344, 20, 1570636800, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (345, 21, 1573315200, 1);
INSERT INTO task_item (id, plan_id, execute_time, status)
VALUES (346, 21, 1575907200, 1);

INSERT INTO user_perms (id, resource_id, module, func, url, description, source, level, status, create_time,
                        update_time, client_id, tenant_id)
VALUES (1, 'jia-oauth', 'action', 'update', 'action/update', 'hasAuthority(''action-update'')', 1, 1, 1, 1715516216763,
        1737531392773, null, null);

INSERT INTO user_perms_rel (role_id, perms_id, update_time, create_time)
VALUES (1, 1, 1560767075, 1560767075);

INSERT INTO user_group (id, client_id, name, code, remark, create_time, update_time, status)
VALUES (1, 'jianO8sbRLofO5XcBxL', '财务主管', 'Finance', '备注', 1553217865, 1553217865, 1);

INSERT INTO user_group_rel (user_id, group_id, update_time, create_time)
VALUES (1, 1, 1553852197, 1553852197);

INSERT INTO user_info (id, username, password, openid, jiacn, phone, email, sex, nickname, avatar, city, country,
                       province, latitude, longitude, point, referrer, birthday, tel, weixin, qq, position, status,
                       remark, msg_type,
                       subscribe, create_time, update_time)
VALUES (1, 'oH2zD1El9hvjnWu-LRmCr-JiTuXI', '123', 'oH2zD1El9hvjnWu-LRmCr-JiTuXI',
        'oH2zD1El9hvjnWu-LRmCr-JiTuXI', '13450909878', null, 1, '张三',
        'avatar/20200621080105_oH2zD1El9hvjnWu-LRmCr-JiTuXI.jpg',
        '东莞',
        '中国', '广东', '22.950199', '113.891899', 165, null, null, null, null, null, '1', null, null, '1',
        'wxd59557202ddff2d5',
        1525668697, 1608162034);

INSERT INTO user_msg (id, title, content, url, type, user_id, status, create_time, update_time)
VALUES (185, '亲爱的UltraChen,您有新的待办任务，请及时处理。', '', 'http://task.test.net', '1', 1, 0, 1560826201,
        1560826201);

INSERT INTO user_org (id, client_id, name, p_id, type, code, remark, role, director, logo, logo_icon, update_time,
                      create_time, status)
VALUES (1, 'jia_client', 'org1', 0, 1, 'FLB', '', '1', '1', null, null, 1524538869, 1524538786, 1);
INSERT INTO user_org (id, client_id, name, p_id, type, code, remark, role, director, logo, logo_icon, update_time,
                      create_time, status)
VALUES (2, 'jia_client', 'org2', 0, 1, 'CHC', '', '2', '2', null, null, 1524538869, 1524538786, 1);
INSERT INTO user_org (id, client_id, name, p_id, type, code, remark, role, director, logo, logo_icon, update_time,
                      create_time, status)
VALUES (3, 'jia_client', 'org3', 1, 1, 'BYA', '', '3', '3', null, null, 1524538869, 1524538786, 1);

INSERT INTO user_org_rel (user_id, org_id, update_time, create_time)
VALUES (1, 1, null, null);

INSERT INTO user_role (id, client_id, name, code, remark, update_time, create_time, status)
VALUES (1, null, '所有权限', 'AdministratorAccess', '该策略允许您管理账户内所有用户及其权限', 1553217909, 1521711133,
        1);

INSERT INTO user_role_rel (user_id, group_id, role_id, client_id, update_time, create_time)
VALUES (1, null, 1, 'jia_client', 1521711133, 1521711133);
INSERT INTO user_role_rel (user_id, group_id, role_id, client_id, update_time, create_time)
VALUES (null, 1, 1, 'jia_client', 1521711133, 1521711133);

INSERT INTO wx_mp_info (acid, client_id, token, access_token, encodingaeskey, level, name, account, original, signature,
                        country, province, city, username, password, create_time, update_time, status, appid, secret,
                        styleid, subscribeurl, auth_refresh_token)
VALUES (1, 'jia_client', 'fanliweiketang', '', '5ljFcG5hVVPWfkhQYNFBP1BNa9jSUERUuzzZnvniG98', 2, '饭粒保',
        'fanliweiketang', 'gh_336235a5d843', '', '', '', '', '1215040519@qq.com', '1838b143c56d3caa37eaffc67a91dc84',
        null, 0, 1, 'wxd59557202ddff2d5', '0f2836c00b6b312a394f01f867126c74', 1, '', '');

INSERT INTO wx_mp_template (template_id, client_id, appid, title, primary_industry, deputy_industry, content, example,
                            status, create_time, update_time)
VALUES ('M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU', 'jia_client', 'wxd59557202ddff2d5', '工作提醒', '金融业', '保险',
        '{{first.DATA}}\\n\\n提醒类型：{{keyword1.DATA}}\\n\\n提醒内容：{{keyword2.DATA}}\\n\\n{{remark.DATA}}', null, 1,
        1, 1);

INSERT INTO wx_mp_user (id, client_id, appid, subscribe, open_id, jiacn, subscribe_time, email, sex, language, nickname,
                        head_img_url, city, country, province, union_id, group_id, subscribe_scene, qr_scene,
                        qr_scene_str, subscribe_items, status, remark, create_time, update_time)
VALUES (1, 'jia_client', 'wxd59557202ddff2d5', 1, 'oH2zD1IyhO9QU415JiBVCrrBcnlg', '7615da6b6c2e422eaabfd62da9eb5da4',
        null, null, 1, null, '大卫',
        'http://thirdwx.qlogo.cn/mmopen/pburdzLK7PUoxy8xxqwwXUtApc0hcwQEBP1MDyCJxovj9cYDjCIibhSylia1OqRQPAAc1vuKAqHPUaMSHLxTu7gAy7fkZfic7Lc/132',
        '大堂区', '中国', '澳门', null, null, null, null, null, null, 1, null, 1595131250, 20200723010937);

INSERT INTO wx_pay_info (acid, client_id, name, account, country, province, city, username, password, create_time,
                         update_time, status, app_id, sub_app_id, mch_id, mch_key, sub_mch_id, notify_url, trade_type,
                         sign_type, key_path, key_content)
VALUES (1, 'jiafewnnv58ec2379c', '超有范', 'fanliweiketang', '', '', '', '1215040519@qq.com',
        '1838b143c56d3caa37eaffc67a91dc84', null, 0, 1, 'wxd59557202ddff2d5', null, '1504956961',
        '0pHgPMSwZ1IlQ1j3plzOrMwXPhVAPpOx', null, null, null, null, 'classpath:wxpay_apiclient_cert.p12', null);

INSERT INTO wx_pay_order (id, appid, mch_id, openid, out_trade_no, product_id, prepay_id, body, detail, total_fee,
                          trade_type, spbill_create_ip, transaction_id, create_time, update_time)
VALUES (1, 'wxd59557202ddff2d5', '1504956961', 'oH2zD1PUPvspicVak69uB4wDaFLg', 'GIF0000007', 'GIF0000002',
        'wx17163900476610de5baa7d0e1239305700', '超有范礼品兑换', '超有范礼品兑换', 1500, 'NATIVE', '101.226.103.70',
        null, 1573979940, 1573979940);
