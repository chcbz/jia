-- 初始化微信调度相关数据
-- 客服消息订阅数据
truncate table kefu_msg_subscribe;
INSERT INTO kefu_msg_subscribe (id, client_id, type_code, jiacn, wx_rx_flag, sms_rx_flag, status, create_time, update_time)
VALUES (1, 'jia_client', 'vote', 'oH2zD1PUPvspicVak69uB4wDaFLg', 1, 0, 1, 1584175547, 1584177170);

INSERT INTO kefu_msg_subscribe (id, client_id, type_code, jiacn, wx_rx_flag, sms_rx_flag, status, create_time, update_time)
VALUES (2, 'jia_client', 'phrase', 'oH2zD1PUPvspicVak69uB4wDaFLg', 1, 0, 1, 1584175547, 1584177170);

-- 客服消息类型数据
truncate table kefu_msg_type;
INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, type_category, wx_template_id, wx_template, wx_template_txt, sms_template_id, status, create_time, update_time)
VALUES (1, 'jia_client', 'vote', '每日一题', 'wxmp', 'M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU',
        '[{\"name\":\"first\",\"value\":\"每天答题时间(两小时有效)\\n\\n#0#\\n\\n#1#\\n\",\"color\":\"#173177\"},{\"name\":\"keyword1\",\"value\":\"知识普及\",\"color\":\"#173177\"},{\"name\":\"keyword2\",\"value\":\"请回复正确答案，退订回复TD\",\"color\":\"#173177\"},{\"name\":\"remark\",\"value\":\"\",\"color\":\"#173177\"}]',
        '每天答题时间(两小时有效)\n#0#\n\n#1#\n\n请回复正确答案，退订回复TD', '', 1, 1584175547, 1584177170);

INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, type_category, wx_template_id, wx_template, wx_template_txt, sms_template_id, status, create_time, update_time)
VALUES (2, 'jia_client', 'gift_delivery', '礼品发货通知', 'admin', 'M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU',
        '[{\"name\":\"first\",\"value\":\"您的礼品#1#已经出货，请留意。\",\"color\":\"#173177\"},{\"name\":\"keyword1\",\"value\":\"礼品发货通知\",\"color\":\"#173177\"},{\"name\":\"keyword2\",\"value\":\"请及时处理\",\"color\":\"#173177\"},{\"name\":\"remark\",\"value\":\"\",\"color\":\"#173177\"}]',
        '', '', 1, 1584175547, 1584177170);