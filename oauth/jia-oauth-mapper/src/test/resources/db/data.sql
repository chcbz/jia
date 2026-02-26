insert into oauth_client (id, client_id, client_id_issued_at, client_secret, client_secret_expires_at,
                                  client_name, client_authentication_methods, authorization_grant_types, redirect_uris,
                                  post_logout_redirect_uris, scopes, client_settings, token_settings, create_time,
                                  update_time, tenant_id, appcn)
values (1, 'jia_client', '2024-5-24', 'secret', '2034-5-24', 'jia客户端', 'client_secret_basic',
        'authorization_code,refresh_token,client_credentials', 'authorized', '', 'openid', '', '', '1716541603993',
        '1716541603993', '', '');