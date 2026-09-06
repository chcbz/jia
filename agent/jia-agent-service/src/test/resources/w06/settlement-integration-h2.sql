-- W06 test-only H2 spelling of the exact additive W04 -> W06 state CHECK migration.
ALTER TABLE agent_task_funding DROP CONSTRAINT chk_agent_task_funding_state;
ALTER TABLE agent_task_funding ADD CONSTRAINT chk_agent_task_funding_state CHECK (
        (funding_status = 'RESERVING' AND version = 0 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NULL AND escrow_version IS NULL AND reserve_transaction_id IS NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'FUNDS_HELD' AND version >= 1 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 0 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'REFUNDED' AND version >= 2 AND remaining_micro = 0
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 1 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NOT NULL AND OCTET_LENGTH(cancel_idempotency_key) = 36
            AND cancel_request_hash IS NOT NULL AND OCTET_LENGTH(cancel_request_hash) = 32
            AND refund_transaction_id IS NOT NULL
            AND cancel_refunded_micro IS NOT NULL AND cancel_refunded_micro > 0
            AND cancel_refunded_micro <= gross_bounty_amount_micro
            AND cancel_task_version IS NOT NULL AND cancel_task_version > 0
            AND refunded_at IS NOT NULL AND refunded_at > 0)
        OR
        (funding_status = 'SETTLED' AND version >= 2 AND remaining_micro = 0
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 1 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
);
