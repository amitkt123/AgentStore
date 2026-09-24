ALTER TABLE wallet ADD COLUMN reserved NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE wallet ADD CONSTRAINT wallet_reserved_non_negative CHECK (reserved >= 0);
ALTER TABLE run ADD COLUMN reserved_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
