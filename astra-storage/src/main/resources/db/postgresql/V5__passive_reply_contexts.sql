CREATE TABLE IF NOT EXISTS astra_reply_contexts(reply_key VARCHAR(64) NOT NULL PRIMARY KEY,chat_key VARCHAR(64) NOT NULL,message_id TEXT NOT NULL,next_seq INTEGER NOT NULL,maximum INTEGER NOT NULL,expires_at BIGINT NOT NULL);
CREATE INDEX IF NOT EXISTS astra_reply_contexts_chat ON astra_reply_contexts(chat_key,expires_at);
