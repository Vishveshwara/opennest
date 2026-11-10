CREATE TABLE public.contact_verifications (
    id uuid CONSTRAINT pk_contact_verifications PRIMARY KEY,
    user_id uuid NOT NULL CONSTRAINT uq_contact_verifications_user UNIQUE,
    phone_number varchar(13) COLLATE "C" NOT NULL,
    telegram_user_id bigint NOT NULL,
    session_digest varchar(64) COLLATE "C" NOT NULL,
    method text NOT NULL DEFAULT 'TELEGRAM_ACCOUNT_CONTACT',
    verified_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_contact_verifications_user FOREIGN KEY (user_id) REFERENCES public.users(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT ck_contact_verifications_phone CHECK (phone_number ~ '^[+]91[0-9]{10}$'),
    CONSTRAINT ck_contact_verifications_telegram CHECK (telegram_user_id > 0),
    CONSTRAINT ck_contact_verifications_session CHECK (session_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_contact_verifications_method CHECK (method = 'TELEGRAM_ACCOUNT_CONTACT')
);
