CREATE TABLE knowledge_chunks(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),kb TEXT NOT NULL,document_id TEXT NOT NULL,content TEXT NOT NULL,source TEXT NOT NULL,metadata TEXT NOT NULL);
CREATE INDEX chunk_scope ON knowledge_chunks(owner,kb,document_id);
CREATE TABLE agent_states(owner TEXT NOT NULL,session_id TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(owner,session_id));
