CREATE TABLE users(id TEXT PRIMARY KEY,email TEXT NOT NULL UNIQUE,display_name TEXT NOT NULL,password_hash TEXT NOT NULL,created_at TEXT NOT NULL);
CREATE TABLE auth_sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),created_at TEXT NOT NULL,last_seen_at TEXT NOT NULL,revoked_at TEXT);
CREATE TABLE resources(kind TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL REFERENCES users(id),parent TEXT NOT NULL DEFAULT '',data TEXT NOT NULL CHECK(json_valid(data)),created_at TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(kind,id));
CREATE INDEX resources_scope ON resources(kind,owner,parent,updated_at);
