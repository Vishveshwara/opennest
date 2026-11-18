CREATE TABLE public.listing_records (
    id uuid CONSTRAINT pk_listing_records PRIMARY KEY,
    owner_id uuid NOT NULL,
    CONSTRAINT uq_listing_records_id_owner UNIQUE (id, owner_id),
    CONSTRAINT fk_listing_records_owner FOREIGN KEY (owner_id) REFERENCES public.users (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE
);

CREATE INDEX ix_listing_records_owner ON public.listing_records (owner_id, id);

LOCK TABLE public.properties IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO public.listing_records (id, owner_id)
SELECT id, owner_id FROM public.properties;

CREATE FUNCTION public.register_listing_record() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    INSERT INTO public.listing_records (id, owner_id) VALUES (NEW.id, NEW.owner_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER register_listing_record BEFORE INSERT ON public.properties
FOR EACH ROW EXECUTE FUNCTION public.register_listing_record();

ALTER TABLE public.properties
    ADD CONSTRAINT fk_properties_listing_record FOREIGN KEY (id, owner_id) REFERENCES public.listing_records (id, owner_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE;

ALTER TABLE public.property_images
    DROP CONSTRAINT fk_property_images_property,
    ADD CONSTRAINT fk_property_images_listing_record FOREIGN KEY (property_id) REFERENCES public.listing_records (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE;

ALTER TABLE public.contact_reveals
    DROP CONSTRAINT fk_contact_reveals_property_owner,
    ADD CONSTRAINT fk_contact_reveals_listing_record FOREIGN KEY (property_id, owner_id) REFERENCES public.listing_records (id, owner_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE;

ALTER TABLE public.owner_reviews
    DROP CONSTRAINT fk_owner_reviews_property_owner,
    ADD CONSTRAINT fk_owner_reviews_listing_record FOREIGN KEY (property_id, owner_id) REFERENCES public.listing_records (id, owner_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT NOT DEFERRABLE;

CREATE TABLE public.listing_object_deletions (
    bucket text NOT NULL,
    object_key text COLLATE "C" NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    CONSTRAINT pk_listing_object_deletions PRIMARY KEY (bucket, object_key),
    CONSTRAINT ck_listing_object_deletions_bucket CHECK (bucket IN ('STAGING', 'FINAL')),
    CONSTRAINT ck_listing_object_deletions_key CHECK (object_key LIKE 'listings/%')
);

CREATE INDEX ix_listing_object_deletions_due ON public.listing_object_deletions (next_attempt_at, bucket, object_key);
