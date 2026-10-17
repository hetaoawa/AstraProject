CREATE TABLE IF NOT EXISTS astra_quotas(quota_key VARCHAR(64) NOT NULL PRIMARY KEY,used_count INTEGER NOT NULL,expires_at BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS astra_quota_members(quota_key VARCHAR(64) NOT NULL,member_key VARCHAR(64) NOT NULL,PRIMARY KEY(quota_key,member_key));
CREATE TABLE IF NOT EXISTS astra_rate_state(state_key VARCHAR(64) NOT NULL PRIMARY KEY,state_json LONGTEXT NOT NULL,expires_at BIGINT NOT NULL);
