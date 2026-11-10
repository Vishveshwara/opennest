CREATE TABLE public.telegram_login_receipts (
    id uuid CONSTRAINT pk_telegram_login_receipts PRIMARY KEY,
    user_id uuid NOT NULL,
    session_digest varchar(64) COLLATE "C" NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT fk_telegram_login_receipts_user FOREIGN KEY (user_id) REFERENCES public.users(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT ck_telegram_login_receipts_session CHECK (session_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_telegram_login_receipts_expiry ON public.telegram_login_receipts (expires_at);
