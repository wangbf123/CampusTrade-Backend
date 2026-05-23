CREATE TABLE user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  nickname VARCHAR(32) NOT NULL,
  phone VARCHAR(20),
  campus VARCHAR(64),
  role VARCHAR(20) NOT NULL DEFAULT 'USER',
  status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  credit_score INT NOT NULL DEFAULT 100,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  seller_id BIGINT NOT NULL,
  title VARCHAR(64) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  category VARCHAR(32) NOT NULL,
  price DECIMAL(10, 2) NOT NULL,
  condition_level VARCHAR(20) NOT NULL,
  campus VARCHAR(64) NOT NULL,
  trade_place VARCHAR(128) NOT NULL,
  status VARCHAR(20) NOT NULL,
  view_count BIGINT NOT NULL DEFAULT 0,
  favorite_count BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_item_status_created (status, created_at),
  INDEX idx_item_category_status (category, status),
  INDEX idx_item_seller (seller_id),
  INDEX idx_item_price (price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE item_image (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  item_id BIGINT NOT NULL,
  image_url VARCHAR(512) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  INDEX idx_item_image_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE favorite (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_favorite_user_item (user_id, item_id),
  INDEX idx_favorite_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE browse_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  viewed_at DATETIME NOT NULL,
  INDEX idx_browse_user_time (user_id, viewed_at),
  INDEX idx_browse_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trade_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(32) NOT NULL UNIQUE,
  item_id BIGINT NOT NULL,
  buyer_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  expected_time DATETIME NOT NULL,
  note VARCHAR(300),
  expire_at DATETIME NOT NULL,
  confirmed_at DATETIME,
  completed_at DATETIME,
  cancel_reason VARCHAR(200),
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_order_buyer (buyer_id, created_at),
  INDEX idx_order_seller (seller_id, created_at),
  INDEX idx_order_item (item_id),
  INDEX idx_order_status_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  operator_id BIGINT NOT NULL,
  reason VARCHAR(200),
  created_at DATETIME NOT NULL,
  INDEX idx_order_event_order (order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) UNIQUE,
  receiver_id BIGINT NOT NULL,
  type VARCHAR(40) NOT NULL,
  title VARCHAR(80) NOT NULL,
  content VARCHAR(500) NOT NULL,
  related_id BIGINT,
  is_read TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  INDEX idx_message_receiver_read (receiver_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  receiver_id BIGINT NOT NULL,
  type VARCHAR(40) NOT NULL,
  title VARCHAR(80) NOT NULL,
  content VARCHAR(500) NOT NULL,
  related_id BIGINT,
  status VARCHAR(20) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at DATETIME,
  last_error VARCHAR(500),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_outbox_status_retry (status, next_retry_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE review (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  target_user_id BIGINT NOT NULL,
  score INT NOT NULL,
  content VARCHAR(300),
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_review_order_reviewer (order_id, reviewer_id),
  INDEX idx_review_target (target_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reporter_id BIGINT NOT NULL,
  target_type VARCHAR(20) NOT NULL,
  target_id BIGINT NOT NULL,
  reason VARCHAR(80) NOT NULL,
  description VARCHAR(500),
  status VARCHAR(20) NOT NULL,
  audit_user_id BIGINT,
  audit_result VARCHAR(500),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_report_status_created (status, created_at),
  INDEX idx_report_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE admin_operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  admin_id BIGINT NOT NULL,
  operation_type VARCHAR(40) NOT NULL,
  target_type VARCHAR(20) NOT NULL,
  target_id BIGINT NOT NULL,
  detail VARCHAR(500),
  created_at DATETIME NOT NULL,
  INDEX idx_admin_log_admin_time (admin_id, created_at),
  INDEX idx_admin_log_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 并发预约确认的核心 SQL：
-- UPDATE item SET status = 'RESERVED', version = version + 1
-- WHERE id = ? AND status = 'ON_SALE';
--
-- 订单状态条件更新：
-- UPDATE trade_order SET status = 'CONFIRMED', version = version + 1
-- WHERE id = ? AND status = 'PENDING';
