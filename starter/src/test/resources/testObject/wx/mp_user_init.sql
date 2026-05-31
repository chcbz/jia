-- 初始化微信用户数据
truncate table wx_mp_user;
INSERT INTO wx_mp_user (id, client_id, appid, subscribe, open_id, jiacn, subscribe_time, email, sex, language, nickname, head_img_url, city, country, province, union_id, group_id, subscribe_scene, qr_scene, qr_scene_str, subscribe_items, status, remark, create_time, update_time)
VALUES (2, 'jia_client', 'wxd59557202ddff2d5', 1, 'oH2zD1PUPvspicVak69uB4wDaFLg', 'oH2zD1PUPvspicVak69uB4wDaFLg', NULL, NULL, 1, NULL, '陈惠超',
        'http://thirdwx.qlogo.cn/mmopen/PiajxSqBRaELiciaN9EEjiaibxyEj2hMPicotQvlSqvhpoNziajxLqfHiaAdrXiaep99Viac8dhqLOLZkJiab3WDt9IBWibiaLQ/132',
        '东莞', '中国', '广东', NULL, NULL, NULL, NULL, NULL, 'vote', 1, NULL, 1595131250, 20200723010937);