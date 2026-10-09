ALTER TABLE notification_outbox
    MODIFY COLUMN next_retry_at DATETIME(6) NULL,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    ADD COLUMN claim_token VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD COLUMN replay_count INT NOT NULL DEFAULT 0,
    ADD INDEX idx_outbox_processing_lease (status, lease_until, id);
