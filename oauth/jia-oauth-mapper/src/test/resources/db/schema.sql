CREATE TABLE oauth_client (
    id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    client_id_issued_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamp DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris varchar(1000) DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings varchar(2000) NOT NULL,
    token_settings varchar(2000) NOT NULL,
    create_time bigint DEFAULT NULL COMMENT '创建时间',
    update_time bigint DEFAULT NULL COMMENT '最后更新时间',
    tenant_id varchar(50) DEFAULT NULL COMMENT '租户ID',
    appcn varchar(32) DEFAULT NULL COMMENT '应用标识码',
    PRIMARY KEY (id)
);

CREATE TABLE oauth_api_key (
    id VARCHAR(100) NOT NULL,
    client_id VARCHAR(100) NOT NULL COMMENT '客户端ID',
    jiacn varchar(32) DEFAULT NULL COMMENT 'Jia账号',
    api_key VARCHAR(255) NOT NULL COMMENT 'API密钥',
    key_name VARCHAR(100) COMMENT '密钥名称',
    status int DEFAULT '1' COMMENT '状态 1有效 0无效',
    expire_time BIGINT COMMENT '过期时间',
    description VARCHAR(500) COMMENT '描述',
    create_time BIGINT COMMENT '创建时间',
    update_time BIGINT COMMENT '更新时间',
    tenant_id VARCHAR(100) COMMENT '租户ID',
    PRIMARY KEY (id),
    INDEX idx_api_key (api_key),
    INDEX idx_client_id (client_id)
) COMMENT='OAuth API密钥表';
