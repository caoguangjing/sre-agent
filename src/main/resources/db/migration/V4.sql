CREATE TABLE sunfire_alerts(alarm_id TEXT PRIMARY KEY,data TEXT NOT NULL CHECK(json_valid(data)),received_at TEXT NOT NULL,expires_at TEXT NOT NULL);
