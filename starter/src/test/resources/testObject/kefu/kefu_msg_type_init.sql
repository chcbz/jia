-- 初始化客服消息类型数据
truncate table kefu_msg_type;
INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, type_category, wx_template_id, wx_template, wx_template_txt, sms_template_id, status, create_time, update_time)
VALUES (1, 'jia_client', 'vote', '每日一题', 'wxmp', 'M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU',
        '[{\"name\":\"first\",\"value\":\"每天答题时间(两小时有效)\\n\\n#0#\\n\\n#1#\\n\",\"color\":\"#173177\"},{\"name\":\"keyword1\",\"value\":\"知识普及\",\"color\":\"#173177\"},{\"name\":\"keyword2\",\"value\":\"请回复正确答案，退订回复TD\",\"color\":\"#173177\"},{\"name\":\"remark\",\"value\":\"\",\"color\":\"#173177\"}]',
        '每天答题时间(两小时有效)\n#0#\n\n#1#\n\n请回复正确答案，退订回复TD', '', 1, 1584175547, 1584177170);

INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, type_category, wx_template_id, wx_template, wx_template_txt, sms_template_id, status, create_time, update_time)
VALUES (2, 'jia_client', 'gift_delivery', '礼品发货通知', 'admin', 'M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU',
        '[{\"name\":\"first\",\"value\":\"您的礼品#1#已经出货，请留意。\",\"color\":\"#173177\"},{\"name\":\"keyword1\",\"value\":\"礼品发货通知\",\"color\":\"#173177\"},{\"name\":\"keyword2\",\"value\":\"请及时处理\",\"color\":\"#173177\"},{\"name\":\"remark\",\"value\":\"\",\"color\":\"#173177\"}]',
        '', '', 1, 1584175547, 1584177170);

INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, type_category, wx_template_id, wx_template, wx_template_txt, sms_template_id, status, create_time, update_time)
VALUES (3, 'jia_client', 'gift_usage', '礼品下单通知', 'point', 'M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU',
        '[{\"name\":\"first\",\"value\":\"有新的下单信息，请及时处理！\",\"color\":\"#173177\"},{\"name\":\"keyword1\",\"value\":\"礼品下单通知\",\"color\":\"#173177\"},{\"name\":\"keyword2\",\"value\":\"请及时处理\",\"color\":\"#173177\"},{\"name\":\"remark\",\"value\":\"\",\"color\":\"#173177\"}]',
        '', '', 1, 1584175547, 1584177170);

INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, type_category, wx_template_id, wx_template, wx_template_txt, sms_template_id, status, create_time, update_time)
VALUES (4, 'jia_client', 'task', '任务通知', 'task', 'M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU',
        '[{\"name\":\"first\",\"value\":\"亲爱的#0#,您有新的待办任务，请及时处理。\",\"color\":\"#173177\"},{\"name\":\"keyword1\",\"value\":\"#1#\",\"color\":\"#173177\"},{\"name\":\"keyword2\",\"value\":\"#2#\",\"color\":\"#173177\"},{\"name\":\"remark\",\"value\":\"#3#\",\"color\":\"#173177\"}]',
        '', '', 1, 1584175547, 1584177170);

INSERT INTO kefu_msg_type (id, client_id, type_code, type_name, type_category, wx_template_id, wx_template, wx_template_txt, sms_template_id, status, create_time, update_time)
VALUES (5, 'jia_client', 'phrase', '每日一句', 'phrase', '',
        '', '#0#', '', 1, 1584175547, 1584177170);