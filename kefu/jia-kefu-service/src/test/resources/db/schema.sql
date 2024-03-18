create table kefu_faq
(
    id          int auto_increment primary key,
    type        varchar(20)   null comment '类型',
    resource_id varchar(50)   null comment '资源ID',
    client_id   varchar(50)   null comment '应用标识符',
    title       varchar(200)  null comment '标题',
    content     varchar(2000) null comment '内容',
    click       int default 0 null comment '点击量',
    useful      int default 0 null comment '点赞数量',
    useless     int default 0 null comment '点踩数量',
    status      int default 1 null comment '状态 0无效 1有效',
    create_time bigint        null comment '创建时间',
    update_time bigint        null comment '最后更新时间'
) comment '常见问题';

create table kefu_message
(
    id          int auto_increment primary key,
    resource_id varchar(50)   null comment '资源ID',
    client_id   varchar(50)   null comment '应用标识符',
    jiacn       varchar(32)   null comment 'Jia账号',
    name        varchar(20)   null comment '姓名',
    phone       varchar(20)   null comment '电话号码',
    email       varchar(100)  null comment '邮箱地址',
    title       varchar(50)   null,
    content     varchar(500)  null,
    attachment  varchar(300)  null,
    reply       varchar(500)  null comment '回复内容',
    status      int default 0 null comment '状态 0待回复 1已回复',
    create_time bigint        null comment '创建时间',
    update_time bigint        null comment '最后更新时间'
) comment '留言信息';

create table kefu_msg_log
(
    id          int auto_increment primary key,
    client_id   varchar(50)   null comment '应用标识符',
    title       varchar(100)  null comment '标题',
    content     varchar(2000) null comment '内容',
    url         varchar(200)  null comment '地址',
    type        varchar(20)   null comment '类型',
    jiacn       varchar(32)   null comment 'Jia账号',
    status      int default 1 null comment '状态 1未读 2已读 0已删除',
    create_time bigint        null comment '创建时间',
    update_time bigint        null comment '最后更新时间'
) comment '消息内容';

create table kefu_msg_subscribe
(
    id          int auto_increment comment 'ID' primary key,
    client_id   varchar(50)   null comment '应用标识符',
    type_code   varchar(50)   not null comment '类型编码',
    jiacn       varchar(32)   not null comment 'Jia账号',
    wx_rx_flag  int default 0 null comment '微信接收',
    sms_rx_flag int default 0 null comment '短信接收',
    status      int default 1 null comment '状态 0失效 1有效',
    create_time bigint        null comment '创建时间',
    update_time bigint        null comment '最后更新时间'
) comment '客户消息订阅';

create table kefu_msg_type
(
    id              int auto_increment comment 'ID' primary key,
    client_id       varchar(50)   null comment '应用标识符',
    type_code       varchar(50)   null comment '类型编码',
    type_name       varchar(50)   null comment '类型名称',
    parent_type     varchar(50)   null comment '父类型',
    type_category   varchar(50)   null comment '类别',
    wx_template_id  varchar(50)   null comment '微信模板ID',
    wx_template     varchar(2000) null comment '微信模板',
    wx_template_txt varchar(2000) null comment '微信文本消息模板',
    sms_template_id varchar(50)   null comment '短信模板ID',
    sms_template    varchar(2000) null comment '短信模板',
    url             varchar(500)  null comment '链接地址',
    status          int default 1 null comment '状态 0失效 1有效',
    create_time     bigint        null comment '创建时间',
    update_time     bigint        null comment '最后更新时间'
) comment '留言类型';

