ALTER TABLE astra_history ADD COLUMN recall_api_flag INTEGER NOT NULL DEFAULT 0;
ALTER TABLE astra_history ADD COLUMN recall_notice_flag INTEGER NOT NULL DEFAULT 0;
UPDATE astra_history SET recall_notice_flag=1 WHERE recalled_flag=1;
ALTER TABLE astra_reply_sequences ADD COLUMN expires_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE astra_events ADD COLUMN completed_at BIGINT;
CREATE TABLE IF NOT EXISTS astra_message_indexes(alias_key VARCHAR(128) PRIMARY KEY,chat_key VARCHAR(128) NOT NULL,index_hash VARCHAR(128) NOT NULL,index_value TEXT NOT NULL,record_key VARCHAR(128) NOT NULL,observed_event VARCHAR(128) NOT NULL,temporary_flag INTEGER NOT NULL);