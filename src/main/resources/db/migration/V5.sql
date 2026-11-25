CREATE TABLE IF NOT EXISTS inspection_settings(
  id INTEGER PRIMARY KEY CHECK(id = 1),
  enabled INTEGER NOT NULL DEFAULT 0,
  last_scan_at TEXT,
  updated_at TEXT NOT NULL
);
INSERT OR IGNORE INTO inspection_settings(id, enabled, last_scan_at, updated_at)
  VALUES(1, 0, NULL, '1970-01-01T00:00:00Z');
