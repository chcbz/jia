create table dwz_record
(
    id          int auto_increment primary key,
    jiacn       varchar(32)   null comment 'Jia账号',
    orig        varchar(1000) null comment '原地址',
    uri         varchar(20)   null comment '目标地址',
    create_time bigint        null comment '记录时间',
    expire_time bigint        null comment '有效时间',
    update_time bigint        null comment '最后更新时间',
    status      int default 1 null comment '状态 1有效 0无效',
    pv          int default 0 null comment '访问量',
    constraint uri unique (uri)
)
comment '短网址记录';