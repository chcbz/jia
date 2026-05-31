-- 初始化微信模板数据
truncate table wx_mp_template;
INSERT INTO wx_mp_template (template_id, client_id, appid, title, primary_industry, deputy_industry, content, example, status, create_time, update_time)
VALUES ('M2LBQMr4BSujBWs5s8xgHcdtNCD69N0q9sO5-EmPnuU', 'jia_client', 'wxd59557202ddff2d5', '工作提醒', '金融业', '保险',
        '{{first.DATA}}\n\n提醒类型：{{keyword1.DATA}}\n\n提醒内容：{{keyword2.DATA}}\n\n{{remark.DATA}}', NULL, 1, 1, 1);