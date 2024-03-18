INSERT INTO mat_media (id, client_id, title, type, url, create_time) VALUES (1, 'jia_client', '长按二维码关注图.png', 1,
'image/1526478392_长按二维码关注图.png', 1526478392);

INSERT INTO mat_phrase (id, client_id, content, tag, create_time, update_time, status, pv, up, down, jiacn) VALUES (1, 'jia_client', '你以为有钱人很快乐吗?他们的快乐你根本想象不到！', '毒鸡汤', 1576631673, 1576631673, 1, 0, 0, 0, 'oH2zD1PUPvspicVak69uB4wDaFLg');
INSERT INTO mat_phrase (id, client_id, content, tag, create_time, update_time, status, pv, up, down, jiacn) VALUES (2, 'jia_client', '时间真的能改变一个人，比如你以前丑，现在更丑。', '毒鸡汤', 1576631673, 1578274619, 1, 1, 0, 0, 'oH2zD1PUPvspicVak69uB4wDaFLg');

INSERT INTO mat_phrase_vote (id, jiacn, phrase_id, vote, create_time) VALUES (1, 'oH2zD1PUPvspicVak69uB4wDaFLg', 1, 1,
1577797034);

INSERT INTO mat_vote (id, client_id, name, start_time, close_time, num) VALUES (1, 'jia_client', '保险基础知识考试试卷', 1572683060, 1604219060, 1121);
INSERT INTO mat_vote_question (id, vote_id, title, multi, point, opt) VALUES (1, 1, '企业、家庭或个人面临的和潜在的风险加以判断、归类和对风险性质进行鉴定的过程是（      ）。', 0, 1, 'C');
INSERT INTO mat_vote_item (id, question_id, opt, content, tick, pic_url, num) VALUES (1, 1, 'A', '风险评价', 0, null, 8);
INSERT INTO mat_vote_item (id, question_id, opt, content, tick, pic_url, num) VALUES (2, 1, 'B', '风险估测', 0, null, 9);
INSERT INTO mat_vote_item (id, question_id, opt, content, tick, pic_url, num) VALUES (3, 1, 'C', '风险识别', 1, null, 28);
