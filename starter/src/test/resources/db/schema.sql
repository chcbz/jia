CREATE TABLE core_dict
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '表id',
    type        varchar(100)  DEFAULT NULL COMMENT '字典类型',
    `language`  varchar(50)   DEFAULT NULL,
    name        varchar(255)  DEFAULT NULL COMMENT '字典名称',
    `value`     varchar(255)  DEFAULT NULL COMMENT '字典值',
    url         varchar(100)  DEFAULT NULL COMMENT '字典文件路径',
    parent_id   varchar(32)   DEFAULT NULL COMMENT '父Id',
    dict_order  int           DEFAULT NULL,
    description varchar(5000) DEFAULT NULL COMMENT '描述',
    status      tinyint(1) DEFAULT '1' COMMENT '状态 1：有效 2：失效',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='字典数据表';

CREATE TABLE core_log
(
    id          bigint NOT NULL AUTO_INCREMENT,
    jiacn       varchar(32)   DEFAULT NULL COMMENT 'Jia账号',
    username    varchar(50)   DEFAULT NULL,
    ip          varchar(20)   DEFAULT NULL COMMENT 'IP地址',
    uri         varchar(100)  DEFAULT NULL COMMENT '访问地址',
    method      varchar(10)   DEFAULT NULL,
    param       varchar(2000) COMMENT '请求参数',
    user_agent  varchar(500)  DEFAULT NULL COMMENT '客户端信息',
    header      varchar(5000) DEFAULT NULL COMMENT '请求头信息',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='日志';

CREATE TABLE core_notice
(
    id          bigint NOT NULL AUTO_INCREMENT,
    type        int         DEFAULT NULL COMMENT '公告类型',
    title       varchar(100) COMMENT '标题',
    content     varchar(2000) COMMENT '内容',
    status      int         DEFAULT '1' COMMENT '状态 0无效 1有效',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='公告';

CREATE TABLE dwz_record
(
    id          bigint NOT NULL AUTO_INCREMENT,
    jiacn       varchar(32)   DEFAULT NULL COMMENT 'Jia账号',
    orig        varchar(1000) DEFAULT NULL COMMENT '原地址',
    uri         varchar(20)   DEFAULT NULL COMMENT '目标地址',
    expire_time bigint        DEFAULT NULL COMMENT '有效时间',
    status      int           DEFAULT '1' COMMENT '状态 1有效 0无效',
    pv          int           DEFAULT '0' COMMENT '访问量',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uri (uri)
) COMMENT='短网址记录';

create table isp_file
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '表id',
    name        varchar(100) null comment '文件名',
    uri         varchar(300) null comment '路径',
    size        bigint null comment '大小（KB）',
    type        int null comment '类型 1公司LOGO 2用户头像',
    extension   varchar(10) null comment '后缀',
    status      int         default 1 null comment '状态 1有效 0无效',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
);

CREATE TABLE kefu_faq
(
    id          bigint NOT NULL AUTO_INCREMENT,
    type        varchar(20)   DEFAULT NULL COMMENT '类型',
    resource_id varchar(50)   DEFAULT NULL COMMENT '资源ID',
    title       varchar(200)  DEFAULT NULL COMMENT '标题',
    content     varchar(2000) DEFAULT NULL COMMENT '内容',
    click       int           DEFAULT '0' COMMENT '点击量',
    useful      int           DEFAULT '0' COMMENT '点赞数量',
    useless     int           DEFAULT '0' COMMENT '点踩数量',
    status      int           DEFAULT '1' COMMENT '状态 0无效 1有效',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='常见问题';

CREATE TABLE kefu_message
(
    id          bigint NOT NULL AUTO_INCREMENT,
    resource_id varchar(50)  DEFAULT NULL COMMENT '资源ID',
    jiacn       varchar(32)  DEFAULT NULL COMMENT 'Jia账号',
    name        varchar(20)  DEFAULT NULL COMMENT '姓名',
    phone       varchar(20)  DEFAULT NULL COMMENT '电话号码',
    email       varchar(100) DEFAULT NULL COMMENT '邮箱地址',
    title       varchar(50)  DEFAULT NULL,
    content     varchar(500) DEFAULT NULL,
    attachment  varchar(300) DEFAULT NULL,
    reply       varchar(500) DEFAULT NULL COMMENT '回复内容',
    status      int          DEFAULT '0' COMMENT '状态 0待回复 1已回复',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='留言信息';

CREATE TABLE kefu_msg_type
(
    id              bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
    type_code       varchar(50)   DEFAULT NULL COMMENT '类型编码',
    type_name       varchar(50)   DEFAULT NULL COMMENT '类型名称',
    parent_type     varchar(50)   DEFAULT NULL COMMENT '父类型',
    type_category   varchar(50)   DEFAULT NULL COMMENT '类别',
    wx_template_id  varchar(50)   DEFAULT NULL COMMENT '微信模板ID',
    wx_template     varchar(2000) DEFAULT NULL COMMENT '微信模板',
    wx_template_txt varchar(2000) DEFAULT NULL COMMENT '微信文本模板',
    sms_template_id varchar(50)   DEFAULT NULL COMMENT '短信模板ID',
    sms_template    varchar(2000) DEFAULT NULL COMMENT '短信模板',
    url             varchar(500)  DEFAULT NULL COMMENT '链接地址',
    status          int           DEFAULT '1' COMMENT '状态 0失效 1有效',
    create_time     bigint        DEFAULT NULL COMMENT '创建时间',
    update_time     bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id       varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id       varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='留言类型';

CREATE TABLE kefu_msg_subscribe
(
    id          bigint      NOT NULL AUTO_INCREMENT COMMENT 'ID',
    type_code   varchar(50) NOT NULL COMMENT '类型编码',
    jiacn       varchar(32) NOT NULL COMMENT 'Jia账号',
    wx_rx_flag  int         DEFAULT '0' COMMENT '微信接收',
    sms_rx_flag int         DEFAULT '0' COMMENT '短信接收',
    status      int         DEFAULT '1' COMMENT '状态 0失效 1有效',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='客户消息订阅';

CREATE TABLE kefu_msg_log
(
    id          bigint NOT NULL AUTO_INCREMENT,
    title       varchar(100)  DEFAULT NULL COMMENT '标题',
    content     varchar(2000) DEFAULT NULL COMMENT '内容',
    url         varchar(200)  DEFAULT NULL COMMENT '地址',
    type        varchar(20)   DEFAULT NULL COMMENT '类型',
    jiacn       varchar(32)   DEFAULT NULL COMMENT 'Jia账号',
    status      int           DEFAULT '1' COMMENT '状态 1未读 2已读 0已删除',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='消息内容';

CREATE TABLE mat_media
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
    title       varchar(100) DEFAULT NULL COMMENT '标题',
    type        int          DEFAULT NULL COMMENT '类型 1图片 2语音 3视频 4缩略图 5富文本',
    url         varchar(200) DEFAULT NULL COMMENT '路径',
    resource_id varchar(50)  DEFAULT NULL COMMENT '资源ID',
    entity_id   bigint       DEFAULT NULL COMMENT '实体ID',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='媒体素材';

CREATE TABLE mat_news
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
    title       varchar(100) DEFAULT NULL COMMENT '标题',
    author      varchar(50)  DEFAULT NULL COMMENT '作者',
    digest      varchar(200) DEFAULT NULL COMMENT '摘要',
    bodyurl     varchar(200) DEFAULT NULL COMMENT '正文链接',
    picurl      varchar(200) DEFAULT NULL COMMENT '缩略图链接',
    resource_id varchar(50)  DEFAULT NULL COMMENT '资源ID',
    entity_id   bigint       DEFAULT NULL COMMENT '实体ID',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='图文素材';

CREATE TABLE mat_phrase
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
    content     varchar(500) DEFAULT NULL COMMENT '内容',
    tag         varchar(100) DEFAULT NULL COMMENT '标签',
    status      int          DEFAULT '1' COMMENT '状态 1有效 0无效',
    pv          int          DEFAULT '0' COMMENT '阅读量',
    up          int          DEFAULT '0' COMMENT '点赞量',
    down        int          DEFAULT '0' COMMENT '点踩量',
    jiacn       varchar(32)  DEFAULT NULL COMMENT '提交人JIA账号',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='短语表';

CREATE TABLE mat_phrase_vote
(
    id          bigint NOT NULL AUTO_INCREMENT,
    jiacn       varchar(32) DEFAULT NULL COMMENT '用户JIA账户',
    phrase_id   bigint      DEFAULT NULL COMMENT '短语表ID',
    vote        int         DEFAULT '0' COMMENT '投票情况 1赞 0踩',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         phrase_vote_id (phrase_id),
    CONSTRAINT mat_phrase_vote_ibfk_1 FOREIGN KEY (phrase_id) REFERENCES mat_phrase (id) ON DELETE CASCADE
) COMMENT='短语投票情况表';

CREATE TABLE mat_pv_log
(
    id          bigint NOT NULL AUTO_INCREMENT,
    resource_id varchar(50)  DEFAULT NULL COMMENT '资源ID',
    entity_id   bigint       DEFAULT NULL COMMENT '实体ID',
    jiacn       varchar(32)  DEFAULT NULL COMMENT 'Jia账号',
    phone       varchar(20)  DEFAULT NULL COMMENT '电话',
    email       varchar(50)  DEFAULT NULL COMMENT '邮箱',
    sex         int          DEFAULT '0' COMMENT '性别 1男性 2女性 0未知',
    nickname    varchar(50)  DEFAULT NULL COMMENT '昵称',
    avatar      varchar(200) DEFAULT NULL COMMENT '头像',
    city        varchar(50)  DEFAULT NULL COMMENT '城市',
    country     varchar(50)  DEFAULT NULL COMMENT '国家',
    province    varchar(50)  DEFAULT NULL COMMENT '省份',
    latitude    varchar(20)  DEFAULT NULL COMMENT '纬度',
    longitude   varchar(20)  DEFAULT NULL COMMENT '经度',
    ip          varchar(20)  DEFAULT NULL COMMENT 'IP地址',
    uri         varchar(200) DEFAULT NULL COMMENT '访问地址',
    user_agent  varchar(500) DEFAULT NULL COMMENT '客户端信息',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='素材访问日志';

CREATE TABLE mat_tip
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '打赏ID',
    type        int         DEFAULT NULL COMMENT '类型 1短语',
    entity_id   bigint      DEFAULT NULL COMMENT '业务实体ID',
    jiacn       varchar(32) DEFAULT NULL COMMENT 'Jia账号',
    price       int         DEFAULT NULL COMMENT '打赏金额（单位：分）',
    status      int         DEFAULT '0' COMMENT '状态 0未支付 1已支付 2已发货',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='打赏情况表';

CREATE TABLE mat_vote
(
    id          bigint NOT NULL AUTO_INCREMENT,
    name        varchar(50) DEFAULT NULL COMMENT '投票名称',
    start_time  bigint      DEFAULT NULL COMMENT '发布时间',
    close_time  bigint      DEFAULT NULL COMMENT '截至时间',
    num         int         DEFAULT '0' COMMENT '投票人数',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='投票管理';

CREATE TABLE mat_vote_question
(
    id          bigint NOT NULL AUTO_INCREMENT,
    vote_id     bigint       DEFAULT NULL COMMENT '投票单ID',
    title       varchar(200) DEFAULT NULL COMMENT '问题',
    multi       int          DEFAULT '0' COMMENT '是否多选 0否 1是',
    point       int          DEFAULT NULL COMMENT '分值',
    opt         varchar(6)   DEFAULT NULL COMMENT '实际答案',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         question_vote_id (vote_id),
    CONSTRAINT mat_vote_question_ibfk_1 FOREIGN KEY (vote_id) REFERENCES mat_vote (id) ON DELETE CASCADE
) COMMENT='投票问题';

CREATE TABLE mat_vote_item
(
    id          bigint NOT NULL AUTO_INCREMENT,
    question_id bigint       DEFAULT NULL COMMENT '问题ID',
    opt         varchar(2)   DEFAULT NULL COMMENT '选项',
    content     varchar(200) DEFAULT NULL COMMENT '内容',
    tick        int          DEFAULT '0' COMMENT '是否正确 1是 0否',
    pic_url     varchar(200) DEFAULT NULL COMMENT '图片地址',
    num         int          DEFAULT '0' COMMENT '票数',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         vote_item_question_id (question_id),
    CONSTRAINT mat_vote_item_ibfk_1 FOREIGN KEY (question_id) REFERENCES mat_vote_question (id) ON DELETE CASCADE
) COMMENT='投票子项';

CREATE TABLE mat_vote_tick
(
    id          bigint NOT NULL AUTO_INCREMENT,
    jiacn       varchar(32) DEFAULT NULL COMMENT '用户JIA账户',
    vote_id     bigint      DEFAULT NULL COMMENT '投票ID',
    question_id bigint      DEFAULT NULL COMMENT '问题ID',
    opt         varchar(6)  DEFAULT NULL COMMENT '选项',
    tick        int         DEFAULT '0' COMMENT '是否正确 1是 0否',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         vote_tick_question_id (question_id),
    KEY         tick_vote_id (vote_id),
    CONSTRAINT mat_vote_tick_ibfk_1 FOREIGN KEY (vote_id) REFERENCES mat_vote (id) ON DELETE CASCADE
) COMMENT='投票情况表';

CREATE TABLE oauth_client
(
    id                            varchar(100)                            NOT NULL,
    client_id                     varchar(100)                            NOT NULL,
    client_id_issued_at           timestamp     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret                 varchar(200)  DEFAULT NULL,
    client_secret_expires_at      timestamp     DEFAULT NULL,
    client_name                   varchar(200)                            NOT NULL,
    client_authentication_methods varchar(1000)                           NOT NULL,
    authorization_grant_types     varchar(1000)                           NOT NULL,
    redirect_uris                 varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris     varchar(1000) DEFAULT NULL,
    scopes                        varchar(1000)                           NOT NULL,
    client_settings               varchar(2000)                           NOT NULL,
    token_settings                varchar(2000)                           NOT NULL,
    create_time                   bigint        DEFAULT NULL COMMENT '创建时间',
    update_time                   bigint        DEFAULT NULL COMMENT '最后更新时间',
    tenant_id                     varchar(50)   DEFAULT NULL COMMENT '租户ID',
    appcn                         varchar(32)   DEFAULT NULL COMMENT '应用标识码',
    PRIMARY KEY (id)
);

CREATE TABLE point_gift
(
    id           bigint NOT NULL AUTO_INCREMENT COMMENT '礼品ID',
    name         varchar(100)  DEFAULT NULL COMMENT '礼品名称',
    description  varchar(1000) DEFAULT NULL COMMENT '礼品描述',
    pic_url      varchar(200)  DEFAULT NULL COMMENT '礼品图片地址',
    point        int           DEFAULT NULL COMMENT '礼品所需积分',
    price        int           DEFAULT NULL COMMENT '价格（单位：分）',
    quantity     int           DEFAULT NULL COMMENT '礼品数量',
    virtual_flag int           DEFAULT '0' COMMENT '是否虚拟物品 0否 1是',
    status       int           DEFAULT '1' COMMENT '状态 1上架 0下架',
    create_time  bigint        DEFAULT NULL COMMENT '创建时间',
    update_time  bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id    varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id    varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='礼品信息';

CREATE TABLE point_gift_usage
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '礼品兑换ID',
    gift_id     bigint        DEFAULT NULL COMMENT '礼品ID',
    name        varchar(100)  DEFAULT NULL COMMENT '礼品名称',
    description varchar(1000) DEFAULT NULL COMMENT '礼品描述',
    pic_url     varchar(200)  DEFAULT NULL COMMENT '图片地址',
    jiacn       varchar(32)   DEFAULT NULL COMMENT 'Jia账号',
    quantity    int           DEFAULT NULL COMMENT '兑换数量',
    point       int           DEFAULT NULL COMMENT '所需积分',
    price       int           DEFAULT NULL COMMENT '消费金额（单位：分）',
    consignee   varchar(50)   DEFAULT NULL COMMENT '收货人',
    phone       varchar(20)   DEFAULT NULL COMMENT '收货电话',
    address     varchar(200)  DEFAULT NULL COMMENT '收货地址',
    card_no     varchar(50)   DEFAULT NULL COMMENT '虚拟卡号',
    status      int           DEFAULT '0' COMMENT '状态 0未支付 1已支付 2已发货 3已收货 4已完成 5已取消',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='礼品兑换情况表';

CREATE TABLE point_record
(
    id          bigint NOT NULL AUTO_INCREMENT,
    jiacn       varchar(32) DEFAULT NULL COMMENT 'Jia账号',
    type        int         DEFAULT NULL COMMENT '积分类型 1新用户 2签到 3推荐 4礼品兑换 5试试手气 6答题 7短语被赞',
    chg         int         DEFAULT NULL COMMENT '积分变化',
    remain      int         DEFAULT NULL COMMENT '剩余积分',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='积分记录';

CREATE TABLE point_referral
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '推荐ID',
    referrer    varchar(32) DEFAULT NULL COMMENT '推荐人Jia账号',
    referral    varchar(32) DEFAULT NULL COMMENT '被推荐人Jia账号',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='推荐信息';

CREATE TABLE point_sign
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '签到ID',
    jiacn       varchar(32)  DEFAULT NULL COMMENT 'Jia账号',
    address     varchar(200) DEFAULT NULL COMMENT '地点',
    latitude    varchar(20)  DEFAULT NULL COMMENT '纬度',
    longitude   varchar(20)  DEFAULT NULL COMMENT '经度',
    point       int          DEFAULT NULL COMMENT '当此得分',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='签到信息';

CREATE TABLE sms_buy
(
    id          bigint NOT NULL AUTO_INCREMENT,
    number      int           DEFAULT NULL COMMENT '充值数量',
    money       decimal(7, 2) DEFAULT NULL COMMENT '充值金额',
    total       int           DEFAULT NULL COMMENT '历史充值数量',
    remain      int           DEFAULT NULL COMMENT '剩余数量',
    status      int           DEFAULT '0' COMMENT '状态 0未支付 1已支付 2已取消',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='短信充值情况';

CREATE TABLE sms_code
(
    id          bigint NOT NULL AUTO_INCREMENT,
    phone       varchar(30) DEFAULT NULL COMMENT '手机号码',
    sms_code    varchar(6)  DEFAULT NULL COMMENT '短信验证码',
    sms_type    int         DEFAULT NULL COMMENT '短信类型  1登录 2忘记密码',
    count       int         DEFAULT '1' COMMENT '发送次数',
    status      int         DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='手机短信表';

CREATE INDEX sms_code_phone_sms_type_status_idx ON sms_code (phone, sms_type, status);
CREATE INDEX sms_code_create_time_idx ON sms_code (create_time);

CREATE TABLE sms_config
(
    client_id   varchar(50) NOT NULL COMMENT '应用标识符',
    short_name  varchar(10)  DEFAULT NULL COMMENT '简称',
    reply_url   varchar(200) DEFAULT NULL COMMENT '短信回复回调地址',
    remain      int          DEFAULT '0' COMMENT '剩余可用数量',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (client_id)
) COMMENT='客户端配置表';

CREATE TABLE sms_message
(
    id          bigint NOT NULL AUTO_INCREMENT,
    template_id varchar(50)  DEFAULT NULL COMMENT '模板ID',
    sender      varchar(50)  DEFAULT NULL COMMENT '发送者',
    receiver    varchar(50)  DEFAULT NULL COMMENT '接收者',
    title       varchar(100) DEFAULT NULL COMMENT '标题',
    content     varchar(500) DEFAULT NULL COMMENT '内容',
    url         varchar(200) DEFAULT NULL COMMENT '链接地址',
    msg_type    int          DEFAULT NULL COMMENT '消息类型 1微信 2邮件 3短息',
    status      int          DEFAULT '0' COMMENT '状态 0未发送 1已发送',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='消息发送';

CREATE TABLE sms_package
(
    id          bigint NOT NULL AUTO_INCREMENT,
    number      int           DEFAULT NULL COMMENT '数量',
    money       decimal(7, 2) DEFAULT NULL COMMENT '金额',
    `order`     int           DEFAULT NULL COMMENT '序列',
    status      int           DEFAULT NULL COMMENT '状态 1有效 0无效',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='短信套餐';

CREATE TABLE sms_reply
(
    id          bigint NOT NULL AUTO_INCREMENT,
    msgid       varchar(30)  DEFAULT NULL,
    mobile      varchar(20)  DEFAULT NULL,
    xh          varchar(10)  DEFAULT NULL,
    content     varchar(500) DEFAULT NULL,
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='短信回复情况表';

CREATE TABLE sms_send
(
    mobile      varchar(20)  DEFAULT NULL COMMENT '手机号码',
    content     varchar(500) DEFAULT NULL COMMENT '短信内容',
    xh          varchar(10)  DEFAULT NULL COMMENT '小号',
    msgid       varchar(30) NOT NULL COMMENT '消息编号',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (msgid)
) COMMENT='短信发送情况表';

CREATE TABLE sms_template
(
    template_id varchar(32) NOT NULL COMMENT '模板ID',
    name        varchar(50)   DEFAULT NULL COMMENT '模板名称',
    title       varchar(500)  DEFAULT NULL COMMENT '模板标题',
    content     varchar(5000) DEFAULT NULL COMMENT '模板内容',
    msg_type    int           DEFAULT NULL COMMENT '消息类型 1微信 2邮件 3短信',
    type        int           DEFAULT NULL COMMENT '模板类型',
    status      int           DEFAULT '0' COMMENT '状态 0待审核 1审核通过 2审核失败',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (template_id)
) COMMENT='短信模板';

CREATE TABLE task_plan
(
    id           bigint      NOT NULL AUTO_INCREMENT,
    jiacn        varchar(32) NOT NULL COMMENT 'JIA账号',
    type         int         NOT NULL COMMENT '任务类型 1常规提醒 2目标 3还款计划 4固定收入',
    period int NOT NULL DEFAULT '0' COMMENT '周期类型 0长期 1每年 2每月 3每周 5每日 11每小时 12每分钟 13每秒 6指定日期',
    crond        varchar(20)          DEFAULT NULL COMMENT '周期表达式',
    name         varchar(30) NOT NULL COMMENT '任务名称',
    description  varchar(200)         DEFAULT NULL COMMENT '任务描述',
    lunar        int                  DEFAULT '0' COMMENT '是否农历日期 1是 0否',
    start_time   bigint               DEFAULT NULL COMMENT '开始时间',
    end_time     bigint               DEFAULT NULL COMMENT '结束时间',
    amount       decimal(10, 2)       DEFAULT NULL COMMENT '数量/金额',
    remind       int         NOT NULL DEFAULT '0' COMMENT '是否需要提醒 1是 0否',
    remind_phone varchar(20)          DEFAULT NULL COMMENT '提醒手机号码',
    remind_msg   varchar(200)         DEFAULT NULL COMMENT '提醒信息',
    status       int         NOT NULL DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time  bigint               DEFAULT NULL COMMENT '创建时间',
    update_time  bigint               DEFAULT NULL COMMENT '最后更新时间',
    client_id    varchar(50)          DEFAULT NULL COMMENT '应用标识符',
    tenant_id    varchar(50)          DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='任务计划';

CREATE TABLE task_item
(
    id           bigint NOT NULL AUTO_INCREMENT,
    plan_id      bigint      DEFAULT NULL COMMENT '计划ID',
    execute_time bigint      DEFAULT NULL COMMENT '时间',
    status       int         DEFAULT '1' COMMENT '状态 1正常 0已失效',
    create_time  bigint      DEFAULT NULL COMMENT '创建时间',
    update_time  bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id    varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id    varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY          plan_id (plan_id),
    CONSTRAINT task_item_ibfk_1 FOREIGN KEY (plan_id) REFERENCES task_plan (id) ON DELETE CASCADE
) COMMENT='任务执行明细';

CREATE VIEW v_task_item AS
select i.id           AS id,
       i.plan_id      AS plan_id,
       p.jiacn        AS jiacn,
       p.type         AS
                         type,
       p.period       AS period,
       p.crond        AS crond,
       p.name         AS name,
       p.description  AS description,
       p.amount       AS amount,
       p.remind       AS
                         remind,
       p.remind_phone AS remind_phone,
       p.remind_msg   AS remind_msg,
       i.status       AS status,
       i.execute_time AS execute_time,
       i.create_time  AS create_time,
       i.update_time  AS update_time,
       i.client_id    AS client_id,
       i.tenant_id    AS tenant_id
from (task_plan p join task_item i on ((p.id = i.plan_id)))
order by i.create_time;

CREATE TABLE user_info
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username    varchar(32)                 null comment '用户名',
    password    varchar(64)  default '123'  null comment '密码',
    openid      varchar(32)                 null comment '微信openid',
    jiacn       varchar(32)                 null comment 'Jia账号',
    phone       varchar(20)                 null comment '电话',
    email       varchar(50)                 null comment '邮箱',
    sex         int                         null comment '性别 1男性 2女性 0未知',
    nickname    varchar(50)                 null comment '昵称',
    avatar      varchar(200)                null comment '头像',
    city        varchar(50)                 null comment '城市',
    country     varchar(50)                 null comment '国家',
    province    varchar(50)                 null comment '省份',
    latitude    varchar(20)                 null comment '纬度',
    longitude   varchar(20)                 null comment '经度',
    point       int          default 0      null comment '积分',
    referrer    varchar(32)                 null comment '推荐人Jia账号',
    birthday    date                        null comment '出生日期',
    tel         varchar(20)                 null comment '固定电话',
    weixin      varchar(20)                 null comment '微信号',
    qq          varchar(20)                 null comment 'QQ号',
    position    varchar(255)                null comment '职位ID',
    status      int                         null comment '状态 1在岗 2出差 0离岗',
    remark      varchar(200)                null comment '简短说明',
    msg_type    varchar(10)  default '1'    null comment '接收消息的方式（多个以逗号隔开） 1微信 2邮箱 3短信',
    subscribe   varchar(500) default 'vote' null comment '订阅内容（多个以逗号隔开）',
    create_time bigint                      null comment '创建时间',
    update_time bigint                      null comment '最后更新时间',
    client_id   varchar(50)                 null comment '应用标识符',
    tenant_id   varchar(50)                 null comment '租户ID',
    weixinid    varchar(32)                 null comment '微信平台用户ID',
    weiboid     varchar(32)                 null comment '微博平台用户ID',
    githubid    varchar(32)                 null comment 'Github平台用户ID',
    location    varchar(100)                null comment '地址',
    PRIMARY KEY (id),
    KEY         user_info_jiacn_idx (jiacn),
    KEY         user_info_openid_idx (openid),
    KEY         user_info_username_idx (username),
    KEY         user_info_phone_idx (phone)
) COMMENT='用户信息';

CREATE TABLE user_group
(
    id          bigint NOT NULL AUTO_INCREMENT,
    name        varchar(100) DEFAULT NULL COMMENT '组名',
    code        varchar(50)  DEFAULT NULL COMMENT '编码',
    remark      varchar(500) DEFAULT NULL COMMENT '备注',
    status      int          DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='用户组';

CREATE TABLE user_group_rel
(
    id          bigint NOT NULL AUTO_INCREMENT,
    user_id     bigint NOT NULL COMMENT '用户ID',
    group_id    bigint NOT NULL COMMENT '组ID',
    status      int         DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         user_group_rel_user_id_idx (user_id),
    KEY         user_group_rel_group_id_idx (group_id)
) COMMENT='用户组关联表';

CREATE TABLE user_msg
(
    id          bigint NOT NULL AUTO_INCREMENT,
    title       varchar(100)  DEFAULT NULL COMMENT '标题',
    content     varchar(2000) DEFAULT NULL COMMENT '内容',
    url         varchar(200)  DEFAULT NULL COMMENT '地址',
    type        varchar(20)   DEFAULT NULL COMMENT '类型',
    user_id     bigint        DEFAULT NULL COMMENT '用户ID',
    status      int           DEFAULT '1' COMMENT '状态 1未读 2已读 0已删除',
    create_time bigint        DEFAULT NULL COMMENT '创建时间',
    update_time bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='消息内容';

CREATE TABLE user_org
(
    id          bigint NOT NULL AUTO_INCREMENT COMMENT '组织ID',
    name        varchar(100) DEFAULT NULL COMMENT '组织名称',
    p_id        bigint       DEFAULT NULL COMMENT '父组织ID',
    type        int          DEFAULT NULL COMMENT '机构类型 1公司 2子公司 3总监 4经理 5职员 6客户',
    code        varchar(50)  DEFAULT NULL COMMENT '机构编码',
    remark      varchar(500) DEFAULT NULL COMMENT '备注',
    role        varchar(50)  DEFAULT NULL COMMENT '角色',
    director    varchar(50)  DEFAULT NULL COMMENT '负责人',
    logo        varchar(200) DEFAULT NULL,
    logo_icon   varchar(200) DEFAULT NULL,
    status      int          DEFAULT NULL COMMENT '状态(1:正常,0:停用)',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         user_org_p_id_idx (p_id)
) COMMENT='组织表';

CREATE TABLE user_org_rel
(
    id          bigint NOT NULL AUTO_INCREMENT,
    user_id     bigint NOT NULL COMMENT '用户ID',
    org_id      bigint NOT NULL COMMENT '组织ID',
    status      int         DEFAULT '1' COMMENT '状态(1:正常,0:停用)',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         user_org_rel_user_id_idx (user_id),
    KEY         user_org_rel_org_id_idx (org_id)
) COMMENT='用户角色关联表';

CREATE TABLE user_role
(
    id          bigint      NOT NULL AUTO_INCREMENT,
    name        varchar(25) NOT NULL COMMENT '角色名',
    code        varchar(20)          DEFAULT NULL COMMENT '编码',
    remark      varchar(200)         DEFAULT NULL COMMENT '备注',
    status      int         NOT NULL DEFAULT '1' COMMENT '状态(1:正常,0停用)',
    create_time bigint               DEFAULT NULL COMMENT '创建时间',
    update_time bigint               DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)          DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)          DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         user_role_name_idx (name)
) COMMENT='角色表';

CREATE TABLE user_role_rel
(
    id          bigint NOT NULL AUTO_INCREMENT,
    user_id     bigint      DEFAULT NULL COMMENT '用户ID',
    group_id    bigint      DEFAULT NULL COMMENT '用户组ID',
    role_id     bigint NOT NULL COMMENT '角色ID',
    status      int         DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         user_role_rel_user_id_idx (user_id),
    KEY         user_role_rel_role_id_idx (role_id),
    KEY         user_role_rel_group_id_idx (group_id)
) COMMENT='用户角色关联表';

CREATE TABLE user_perms
(
    id          bigint      NOT NULL AUTO_INCREMENT,
    resource_id varchar(50)          DEFAULT NULL COMMENT '应用标识符',
    module      varchar(30) NOT NULL COMMENT '模块',
    func        varchar(50) NOT NULL COMMENT '方法',
    url         varchar(100)         DEFAULT NULL COMMENT '服务地址',
    description varchar(50)          DEFAULT NULL COMMENT '备注',
    source      int                  DEFAULT '1' COMMENT '数据来源 1系统生成 2手动创建',
    level       int                  DEFAULT '1' COMMENT '权限级别 1总后台 2企业后台',
    status      int         NOT NULL DEFAULT '1' COMMENT '状态(1:正常,0:停用)',
    create_time bigint               DEFAULT NULL COMMENT '创建时间',
    update_time bigint               DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)          DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)          DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    UNIQUE KEY d_c_f (module,func,resource_id) USING BTREE
) COMMENT='资源表';

CREATE TABLE user_perms_rel
(
    id          bigint NOT NULL AUTO_INCREMENT,
    role_id     bigint NOT NULL COMMENT '角色ID',
    perms_id    bigint NOT NULL COMMENT '权限ID',
    expire_time bigint      DEFAULT NULL COMMENT '过期时间',
    status      int         DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time bigint      DEFAULT NULL COMMENT '创建时间',
    update_time bigint      DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50) DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50) DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id),
    KEY         user_perms_rel_role_id_idx (role_id),
    KEY         user_perms_rel_perms_id_idx (perms_id)
) COMMENT='角色与权限对应表';

CREATE TABLE wx_mp_info
(
    acid               bigint unsigned NOT NULL AUTO_INCREMENT,
    token              varchar(32)  NOT NULL COMMENT '令牌',
    access_token       varchar(1000) DEFAULT NULL,
    encodingaeskey     varchar(255) NOT NULL COMMENT '消息加解密密钥',
    level              tinyint unsigned NOT NULL COMMENT '认证类别 1普通订阅号 2普通服务号 3认证订阅号 4认证服务号/认证媒体/政府订阅号',
    name               varchar(30)  NOT NULL COMMENT '公众号名称',
    account            varchar(30)  NOT NULL COMMENT '公众号帐号',
    original           varchar(50)  NOT NULL COMMENT '原始ID',
    signature          varchar(100)  DEFAULT NULL COMMENT '介绍',
    country            varchar(10)   DEFAULT NULL COMMENT '国家',
    province           varchar(3)    DEFAULT NULL COMMENT '省份',
    city               varchar(15)   DEFAULT NULL COMMENT '城市',
    username           varchar(30)  NOT NULL COMMENT '登录账号',
    password           varchar(32)  NOT NULL COMMENT '登录密码',
    create_time        bigint        DEFAULT NULL COMMENT '创建时间',
    update_time        bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id          varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id          varchar(50)   DEFAULT NULL COMMENT '租户ID',
    status             int           DEFAULT '1' COMMENT '状态 1有效 0无效',
    appid              varchar(50)  NOT NULL COMMENT '开发者ID',
    secret             varchar(50)  NOT NULL COMMENT '开发者密码',
    styleid            bigint unsigned DEFAULT NULL,
    subscribeurl       varchar(120)  DEFAULT NULL,
    auth_refresh_token varchar(255)  DEFAULT NULL,
    PRIMARY KEY (acid),
    KEY                wx_mp_info_idx_key (appid) USING BTREE
) COMMENT='微信公众号信息';

CREATE TABLE wx_mp_template
(
    template_id      varchar(50) NOT NULL COMMENT '模板ID',
    appid            varchar(50)  DEFAULT NULL COMMENT '开发者ID',
    title            varchar(50)  DEFAULT NULL COMMENT '标题',
    primary_industry varchar(30)  DEFAULT NULL COMMENT '主要行业',
    deputy_industry  varchar(30)  DEFAULT NULL COMMENT '子行业',
    content          varchar(500) DEFAULT NULL COMMENT '模板内容',
    example          varchar(500) DEFAULT NULL COMMENT '示例',
    status           int          DEFAULT '1' COMMENT '状态 1有效 0无效',
    create_time      bigint       DEFAULT NULL COMMENT '创建时间',
    update_time      bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id        varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id        varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (template_id)
) COMMENT='微信公众号消息模板表';

CREATE TABLE wx_mp_user
(
    id               bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    appid            varchar(50)   DEFAULT NULL COMMENT '开发者ID',
    subscribe        tinyint(1) DEFAULT NULL COMMENT '是否订阅',
    open_id          varchar(32)   DEFAULT NULL COMMENT '微信openid',
    jiacn            varchar(32)   DEFAULT NULL COMMENT 'Jia账号',
    subscribe_time   bigint        DEFAULT NULL COMMENT '电话',
    email            varchar(50)   DEFAULT NULL COMMENT '邮箱',
    sex              int           DEFAULT '0' COMMENT '性别 1男性 2女性 0未知',
    language         varchar(10)   DEFAULT NULL COMMENT '语言',
    nickname         varchar(50)   DEFAULT NULL COMMENT '昵称',
    head_img_url     varchar(200)  DEFAULT NULL COMMENT '头像',
    city             varchar(50)   DEFAULT NULL COMMENT '城市',
    country          varchar(50)   DEFAULT NULL COMMENT '国家',
    province         varchar(50)   DEFAULT NULL COMMENT '省份',
    union_id         varchar(255)  DEFAULT NULL COMMENT '开放平台帐号',
    group_id         bigint        DEFAULT NULL COMMENT '组ID',
    subscribe_scene  varchar(50)   DEFAULT NULL COMMENT '关注的渠道来源',
    qr_scene         varchar(100)  DEFAULT NULL COMMENT '二维码扫码场景',
    qr_scene_str     varchar(200)  DEFAULT NULL COMMENT '二维码扫码场景描述',
    subscribe_items  varchar(2000) DEFAULT NULL COMMENT '订阅服务明细',
    status           int           DEFAULT '1' COMMENT '状态 1有效 0无效',
    remark           varchar(200)  DEFAULT NULL COMMENT '简短说明',
    latest_auth_code varchar(255)  DEFAULT NULL COMMENT '最新授权码',
    create_time      bigint        DEFAULT NULL COMMENT '创建时间',
    update_time      bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id        varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id        varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='微信公众号用户信息';

CREATE TABLE wx_pay_info
(
    acid        bigint unsigned NOT NULL AUTO_INCREMENT,
    name        varchar(30) NOT NULL COMMENT '公众号名称',
    account     varchar(30) NOT NULL COMMENT '公众号帐号',
    country     varchar(10)  DEFAULT NULL COMMENT '国家',
    province    varchar(3)   DEFAULT NULL COMMENT '省份',
    city        varchar(15)  DEFAULT NULL COMMENT '城市',
    username    varchar(30) NOT NULL COMMENT '登录账号',
    password    varchar(32) NOT NULL COMMENT '登录密码',
    status      int          DEFAULT '1' COMMENT '状态 1有效 0无效',
    app_id      varchar(50) NOT NULL COMMENT '开发者ID',
    sub_app_id  varchar(50)  DEFAULT NULL COMMENT '子商户公众账号ID',
    mch_id      varchar(50) NOT NULL COMMENT '商户号',
    mch_key     varchar(32) NOT NULL COMMENT '商户密钥',
    sub_mch_id  varchar(50)  DEFAULT NULL COMMENT '子商户号',
    notify_url  varchar(100) DEFAULT NULL COMMENT '回调地址',
    trade_type  varchar(20)  DEFAULT NULL COMMENT '交易类型 JSAPI--公众号支付 NATIVE--原生扫码支付 APP--app支付',
    sign_type   varchar(20)  DEFAULT NULL COMMENT '签名方式 HMAC_SHA256和MD5',
    key_path    varchar(200) DEFAULT NULL COMMENT '证书文件路径',
    key_content varchar(200) DEFAULT NULL COMMENT '证书文件内容',
    create_time bigint       DEFAULT NULL COMMENT '创建时间',
    update_time bigint       DEFAULT NULL COMMENT '最后更新时间',
    client_id   varchar(50)  DEFAULT NULL COMMENT '应用标识符',
    tenant_id   varchar(50)  DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (acid),
    KEY         wx_pay_info_idx_key (app_id) USING BTREE
) COMMENT='微信支付信息';

CREATE TABLE wx_pay_order
(
    id               bigint NOT NULL AUTO_INCREMENT,
    appid            varchar(32)   DEFAULT NULL COMMENT '公众账号ID',
    mch_id           varchar(32)   DEFAULT NULL COMMENT '商户号',
    openid           varchar(128)  DEFAULT NULL COMMENT '用户标识',
    out_trade_no     varchar(32)   DEFAULT NULL COMMENT '商户订单号',
    product_id       varchar(32)   DEFAULT NULL COMMENT '商品ID',
    prepay_id        varchar(64)   DEFAULT NULL COMMENT '预支付ID',
    body             varchar(128)  DEFAULT NULL COMMENT '商品描述',
    detail           varchar(6000) DEFAULT NULL COMMENT '商品详情',
    total_fee        int           DEFAULT NULL COMMENT '标价金额',
    trade_type       varchar(16)   DEFAULT NULL COMMENT '交易类型',
    spbill_create_ip varchar(64)   DEFAULT NULL COMMENT '终端IP',
    transaction_id   varchar(32)   DEFAULT NULL COMMENT '微信支付订单号',
    create_time      bigint        DEFAULT NULL COMMENT '创建时间',
    update_time      bigint        DEFAULT NULL COMMENT '最后更新时间',
    client_id        varchar(50)   DEFAULT NULL COMMENT '应用标识符',
    tenant_id        varchar(50)   DEFAULT NULL COMMENT '租户ID',
    PRIMARY KEY (id)
) COMMENT='微信支付情况表';