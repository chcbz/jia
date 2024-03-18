create table isp_file
(
    id          bigint auto_increment primary key,
    client_id   varchar(50)   null comment '应用标识符',
    name        varchar(100)  null comment '文件名',
    uri         varchar(300)  null comment '路径',
    size        bigint        null comment '大小（KB）',
    type        int           null comment '类型 1公司LOGO 2用户头像',
    extension   varchar(10)   null comment '后缀',
    status      int default 1 null comment '状态 1有效 0无效',
    create_time bigint        null comment '创建时间',
    update_time bigint        null comment '最后更新时间'
);
