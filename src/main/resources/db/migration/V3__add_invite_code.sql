CREATE TABLE invite_code (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL UNIQUE,
  created_by BIGINT NOT NULL,
  max_uses INT NOT NULL DEFAULT 1,
  used_count INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL,
  expires_at DATETIME,
  remark VARCHAR(200),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_invite_code_status_created (status, created_at),
  INDEX idx_invite_code_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
