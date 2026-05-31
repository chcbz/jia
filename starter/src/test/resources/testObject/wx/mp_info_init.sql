-- 初始化微信公众号信息数据
truncate table wx_mp_info;
INSERT INTO wx_mp_info (acid, client_id, token, access_token, encodingaeskey, level, name, account, original, signature, country, province, city, username, password, update_time, status, appid, secret, styleid, subscribeurl, auth_refresh_token)
VALUES (1, 'jia_client', 'fanliweiketang', '', '5ljFcG5hVVPWfkhQYNFBP1BNa9jSUERUuzzZnvniG98', 2, '饭粒保', 'fanliweiketang', 'gh_336235a5d843', '', '', '', '', '1215040519@qq.com', '1838b143c56d3caa37eaffc67a91dc84', 0, 1, 'wxd59557202ddff2d5', '0f2836c00b6b312a394f01f867126c74', 1, '', '');