ALTER TABLE item
  ADD INDEX idx_item_status_campus_created (status, campus, created_at, id),
  ADD INDEX idx_item_status_category_campus_created (status, category, campus, created_at, id),
  ADD INDEX idx_item_status_price_created (status, price, created_at, id);
