CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

CREATE TABLE public.users (
    id uuid CONSTRAINT pk_users PRIMARY KEY,
    full_name text NOT NULL,
    phone_number varchar(13) COLLATE "C",
    phone_verified_at timestamptz,
    role text NOT NULL DEFAULT 'USER',
    average_rating numeric,
    review_count bigint NOT NULL DEFAULT 0,
    broker_report_count bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT uq_users_phone_number UNIQUE (phone_number),
    CONSTRAINT ck_users_name CHECK (length(btrim(full_name)) > 0),
    CONSTRAINT ck_users_phone_format CHECK (phone_number ~ '^[+]91[0-9]{10}$'),
    CONSTRAINT ck_users_verified_phone CHECK (
        (phone_number IS NULL AND phone_verified_at IS NULL)
        OR (phone_number IS NOT NULL AND phone_verified_at IS NOT NULL)
    ),
    CONSTRAINT ck_users_role CHECK (role = 'USER'),
    CONSTRAINT ck_users_review_count CHECK (review_count >= 0),
    CONSTRAINT ck_users_report_count CHECK (broker_report_count BETWEEN 0 AND review_count),
    CONSTRAINT ck_users_rating_state CHECK (
        (review_count = 0 AND average_rating IS NULL)
        OR (review_count > 0 AND average_rating IS NOT NULL AND average_rating BETWEEN 1 AND 5)
    )
);

CREATE TABLE public.auth_identities (
    id uuid CONSTRAINT pk_auth_identities PRIMARY KEY,
    user_id uuid NOT NULL,
    issuer text COLLATE "C" NOT NULL,
    subject text COLLATE "C" NOT NULL,
    email text,
    email_verified boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_auth_identities_user FOREIGN KEY (user_id) REFERENCES public.users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT uq_auth_identities_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT ck_auth_identities_issuer CHECK (length(btrim(issuer)) > 0),
    CONSTRAINT ck_auth_identities_subject CHECK (length(btrim(subject)) > 0),
    CONSTRAINT ck_auth_identities_email CHECK (email IS NULL OR length(btrim(email)) > 0),
    CONSTRAINT ck_auth_identities_email_claim CHECK (email IS NOT NULL OR email_verified = false)
);

CREATE TABLE public.properties (
    id uuid CONSTRAINT pk_properties PRIMARY KEY,
    owner_id uuid NOT NULL,
    title text NOT NULL,
    description text NOT NULL,
    category text NOT NULL,
    flat_rental_mode text,
    status text NOT NULL DEFAULT 'PAUSED',
    version bigint NOT NULL DEFAULT 0,
    city text COLLATE "C" NOT NULL,
    locality text COLLATE "C" NOT NULL,
    private_address text NOT NULL,
    latitude numeric(9,6) NOT NULL,
    longitude numeric(9,6) NOT NULL,
    location public.geography(Point,4326) GENERATED ALWAYS AS (
        public.ST_SetSRID(public.ST_MakePoint(longitude::double precision, latitude::double precision), 4326)::public.geography
    ) STORED NOT NULL,
    monthly_rent integer NOT NULL,
    security_deposit integer NOT NULL,
    bedrooms integer,
    has_wifi boolean NOT NULL DEFAULT false,
    has_gym boolean NOT NULL DEFAULT false,
    has_swimming_pool boolean NOT NULL DEFAULT false,
    whole_flat_available boolean,
    max_occupants integer,
    male_rooms_available integer,
    female_rooms_available integer,
    shared_rooms_available integer,
    total_rooms_available bigint GENERATED ALWAYS AS (
        CASE WHEN category = 'FLAT' AND flat_rental_mode = 'ENTIRE_FLAT' THEN NULL
        ELSE male_rooms_available::bigint + female_rooms_available::bigint + shared_rooms_available::bigint END
    ) STORED,
    max_sharing_per_room integer,
    nearby_landmarks jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_properties_owner FOREIGN KEY (owner_id) REFERENCES public.users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT uq_properties_id_owner UNIQUE (id, owner_id),
    CONSTRAINT ck_properties_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_properties_description CHECK (length(btrim(description)) > 0),
    CONSTRAINT ck_properties_city CHECK (length(btrim(city)) > 0),
    CONSTRAINT ck_properties_locality CHECK (length(btrim(locality)) > 0),
    CONSTRAINT ck_properties_address CHECK (length(btrim(private_address)) > 0),
    CONSTRAINT ck_properties_category CHECK (category IN ('FLAT', 'PG_HOSTEL')),
    CONSTRAINT ck_properties_flat_mode CHECK (flat_rental_mode IN ('ENTIRE_FLAT', 'ROOM')),
    CONSTRAINT ck_properties_status CHECK (status IN ('ACTIVE', 'FULL_OCCUPIED', 'PAUSED')),
    CONSTRAINT ck_properties_version CHECK (version >= 0),
    CONSTRAINT ck_properties_latitude CHECK (latitude BETWEEN 6.5 AND 37.5),
    CONSTRAINT ck_properties_longitude CHECK (longitude BETWEEN 68 AND 97.5),
    CONSTRAINT ck_properties_rent CHECK (monthly_rent > 0),
    CONSTRAINT ck_properties_deposit CHECK (security_deposit >= 0),
    CONSTRAINT ck_properties_landmarks CHECK (jsonb_typeof(nearby_landmarks) = 'array'),
    CONSTRAINT ck_properties_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_properties_mode_inventory CHECK ((
        (category = 'FLAT' AND flat_rental_mode IS NOT NULL AND flat_rental_mode = 'ENTIRE_FLAT'
            AND bedrooms IS NOT NULL AND bedrooms >= 0
            AND whole_flat_available IS NOT NULL AND max_occupants IS NOT NULL AND max_occupants > 0
            AND male_rooms_available IS NULL AND female_rooms_available IS NULL AND shared_rooms_available IS NULL
            AND max_sharing_per_room IS NULL)
        OR (category = 'FLAT' AND flat_rental_mode IS NOT NULL AND flat_rental_mode = 'ROOM'
            AND bedrooms IS NOT NULL AND bedrooms >= 0
            AND whole_flat_available IS NULL AND max_occupants IS NULL
            AND male_rooms_available IS NOT NULL AND male_rooms_available >= 0
            AND female_rooms_available IS NOT NULL AND female_rooms_available >= 0
            AND shared_rooms_available IS NOT NULL AND shared_rooms_available >= 0
            AND max_sharing_per_room IS NOT NULL AND max_sharing_per_room BETWEEN 1 AND 10)
        OR (category = 'PG_HOSTEL' AND flat_rental_mode IS NULL AND bedrooms IS NULL
            AND whole_flat_available IS NULL AND max_occupants IS NULL
            AND male_rooms_available IS NOT NULL AND male_rooms_available >= 0
            AND female_rooms_available IS NOT NULL AND female_rooms_available >= 0
            AND shared_rooms_available IS NOT NULL AND shared_rooms_available >= 0
            AND max_sharing_per_room IS NOT NULL AND max_sharing_per_room BETWEEN 1 AND 10)
    ) IS TRUE),
    CONSTRAINT ck_properties_publication_inventory CHECK ((
        CASE
            WHEN status = 'PAUSED' THEN true
            WHEN category = 'FLAT' AND flat_rental_mode = 'ENTIRE_FLAT' THEN
                (status = 'ACTIVE' AND whole_flat_available = true)
                OR (status = 'FULL_OCCUPIED' AND whole_flat_available = false)
            ELSE (status = 'ACTIVE' AND total_rooms_available > 0)
                OR (status = 'FULL_OCCUPIED' AND total_rooms_available = 0)
        END
    ) IS TRUE)
);

CREATE TABLE public.property_images (
    id uuid CONSTRAINT pk_property_images PRIMARY KEY,
    property_id uuid NOT NULL,
    upload_object_key text COLLATE "C" NOT NULL,
    object_key text COLLATE "C",
    segment text NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    upload_expires_at timestamptz NOT NULL,
    validation_state text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_property_images_property FOREIGN KEY (property_id) REFERENCES public.properties (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT uq_property_images_upload_key UNIQUE (upload_object_key),
    CONSTRAINT uq_property_images_object_key UNIQUE (object_key),
    CONSTRAINT ck_property_images_upload_key CHECK (length(btrim(upload_object_key)) > 0),
    CONSTRAINT ck_property_images_object_key CHECK (object_key IS NULL OR length(btrim(object_key)) > 0),
    CONSTRAINT ck_property_images_segment CHECK (segment IN ('ROOM', 'HALL', 'BATHROOM', 'KITCHEN', 'EXTERIOR_AMENITY')),
    CONSTRAINT ck_property_images_order CHECK (display_order >= 0),
    CONSTRAINT ck_property_images_expiry CHECK (upload_expires_at > created_at),
    CONSTRAINT ck_property_images_state CHECK (validation_state IN ('PENDING', 'VALIDATED', 'REJECTED')),
    CONSTRAINT ck_property_images_final_key CHECK ((
        (validation_state = 'VALIDATED' AND object_key IS NOT NULL AND object_key <> upload_object_key)
        OR (validation_state IN ('PENDING', 'REJECTED') AND object_key IS NULL)
    ) IS TRUE)
);

CREATE TABLE public.contact_reveals (
    id uuid CONSTRAINT pk_contact_reveals PRIMARY KEY,
    tenant_id uuid NOT NULL,
    property_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    revealed_date date GENERATED ALWAYS AS ((created_at AT TIME ZONE 'Asia/Kolkata')::date) STORED NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_contact_reveals_tenant FOREIGN KEY (tenant_id) REFERENCES public.users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT fk_contact_reveals_property_owner FOREIGN KEY (property_id, owner_id) REFERENCES public.properties (id, owner_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT uq_contact_reveals_daily_pair UNIQUE (tenant_id, property_id, revealed_date),
    CONSTRAINT ck_contact_reveals_not_self CHECK (tenant_id <> owner_id)
);

CREATE TABLE public.owner_reviews (
    id uuid CONSTRAINT pk_owner_reviews PRIMARY KEY,
    property_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    reviewer_id uuid NOT NULL,
    rating smallint NOT NULL,
    issue_tag text NOT NULL,
    comment_text text,
    is_auto_broker_report boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT fk_owner_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES public.users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT fk_owner_reviews_property_owner FOREIGN KEY (property_id, owner_id) REFERENCES public.properties (id, owner_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE,
    CONSTRAINT uq_owner_reviews_reviewer_property UNIQUE (reviewer_id, property_id),
    CONSTRAINT ck_owner_reviews_not_self CHECK (reviewer_id <> owner_id),
    CONSTRAINT ck_owner_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_owner_reviews_tag CHECK (issue_tag IN ('GENUINE_OWNER', 'ASKED_BROKERAGE', 'MAINTENANCE_ISSUE', 'FAKE_PHOTOS', 'OTHER')),
    CONSTRAINT ck_owner_reviews_comment CHECK (comment_text IS NULL OR length(btrim(comment_text)) > 0),
    CONSTRAINT ck_owner_reviews_report_shape CHECK (
        (is_auto_broker_report = true AND rating = 1 AND issue_tag = 'ASKED_BROKERAGE')
        OR (is_auto_broker_report = false AND issue_tag <> 'ASKED_BROKERAGE')
    )
);
