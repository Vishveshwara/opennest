CREATE TABLE public.sandbox_state (
    id smallint CONSTRAINT pk_sandbox_state PRIMARY KEY,
    seed_version integer NOT NULL,
    generation bigint NOT NULL,
    reset_interval bigint NOT NULL,
    reset_at timestamptz NOT NULL,
    CONSTRAINT ck_sandbox_state_singleton CHECK (id = 1),
    CONSTRAINT ck_sandbox_state_version CHECK (seed_version > 0),
    CONSTRAINT ck_sandbox_state_generation CHECK (generation > 0),
    CONSTRAINT ck_sandbox_state_interval CHECK (reset_interval >= 0)
);
