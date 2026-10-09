ALTER TABLE item ADD COLUMN reserved_order_id BIGINT NULL;

-- Bind legacy reservations only when there is exactly one confirmed order.
-- Ambiguous legacy data fails closed: it needs operator reconciliation.
UPDATE item i
JOIN (
    SELECT item_id, MIN(id) AS order_id
    FROM trade_order
    WHERE status = 'CONFIRMED'
    GROUP BY item_id
    HAVING COUNT(*) = 1
) o ON o.item_id = i.id
SET i.reserved_order_id = o.order_id
WHERE i.status = 'RESERVED';

CREATE TABLE appointment_idempotency (
    user_id BIGINT NOT NULL,
    request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id BIGINT NULL,
    response_json JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, request_key),
    INDEX idx_appointment_idempotency_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
