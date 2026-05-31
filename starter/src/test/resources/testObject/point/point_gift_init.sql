-- 初始化礼品数据
truncate table point_gift;
INSERT INTO point_gift (id, name, description, pic_url, point, price, quantity, virtual_flag, status, create_time, update_time, client_id, tenant_id)
VALUES (1, '国寿乐学无忧保险产品', '3-25周岁学生儿童均可投保，尊享5万意外医疗和10万住院医疗，保障额度更高，范围更全面，保费更低廉！',
        'https://mat.chaoyoufan.net/image/lexuewuyou.jpg', 168, 16800, 86, 1, 1, 1524732346, 1596426574, 'jia_client', NULL);