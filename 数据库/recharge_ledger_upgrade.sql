USE db_beadhouse;

-- Only new, successfully committed recharges receive a receipt. Existing balances
-- and historical charges are preserved without fabricating historical receipts.
CREATE TABLE IF NOT EXISTS deposit_recharge_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    elder_id BIGINT NOT NULL,
    staff_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    balance_before DECIMAL(10,2) NOT NULL,
    balance_after DECIMAL(10,2) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_recharge_request_id (request_id),
    KEY idx_recharge_elder_time (elder_id, create_time, id),
    CONSTRAINT chk_recharge_positive_amount CHECK (amount > 0),
    CONSTRAINT chk_recharge_nonnegative_balance CHECK (balance_before >= 0 AND balance_after >= 0),
    CONSTRAINT chk_recharge_balance_delta CHECK (balance_after = balance_before + amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已入账充值凭证；业务不允许修改或删除';
