ALTER TABLE message
  ADD INDEX idx_message_receiver_created (receiver_id, created_at, id);
