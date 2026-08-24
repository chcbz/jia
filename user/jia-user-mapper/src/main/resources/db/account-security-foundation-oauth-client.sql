-- Prerequisite: the mode-600 preflight capture contains exactly one target row and its generated rollback SQL.
-- Abort release if client_settings/token_settings are not valid JSON or the target row count is not exactly one.
UPDATE oauth_client
SET client_secret = NULL,
    client_secret_expires_at = NULL,
    client_authentication_methods = 'none',
    authorization_grant_types = 'authorization_code',
    redirect_uris = 'https://kit.chaoyoufan.cn/oauth2/callback',
    scopes = 'openid',
    client_settings = JSON_SET(client_settings,
        '$."settings.client.require-proof-key"', CAST('true' AS JSON)),
    token_settings = JSON_SET(token_settings,
        '$."settings.token.access-token-time-to-live"', 'PT10M',
        '$."settings.token.authorization-code-time-to-live"', 'PT5M')
WHERE client_id = 'jiafewnnv58ec2379c'
  AND JSON_VALID(client_settings)
  AND JSON_VALID(token_settings)
  AND 1 = (
      SELECT target_count
      FROM (
          SELECT COUNT(*) AS target_count
          FROM oauth_client
          WHERE client_id = 'jiafewnnv58ec2379c'
      ) AS target_guard
  );

SELECT ROW_COUNT() AS migrated_target_client_rows;
SELECT client_id, client_secret, client_secret_expires_at, client_authentication_methods,
       authorization_grant_types, redirect_uris, scopes,
       JSON_EXTRACT(client_settings, '$."settings.client.require-proof-key"') AS require_proof_key,
       JSON_UNQUOTE(JSON_EXTRACT(token_settings, '$."settings.token.access-token-time-to-live"')) AS access_token_ttl,
       JSON_UNQUOTE(JSON_EXTRACT(token_settings, '$."settings.token.authorization-code-time-to-live"')) AS authorization_code_ttl
FROM oauth_client WHERE client_id = 'jiafewnnv58ec2379c';
