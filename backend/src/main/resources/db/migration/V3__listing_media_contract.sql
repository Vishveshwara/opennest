CREATE VIEW public.listing_owner_profiles AS
SELECT id, full_name, average_rating, review_count, broker_report_count FROM public.users;

ALTER TABLE public.property_images
    ADD COLUMN declared_content_type text,
    ADD COLUMN declared_byte_size bigint,
    ADD COLUMN content_type text,
    ADD COLUMN byte_size bigint,
    ADD COLUMN width integer,
    ADD COLUMN height integer,
    ADD CONSTRAINT ck_property_images_declared_metadata CHECK (
        num_nonnulls(declared_content_type, declared_byte_size) = 0 OR
        (num_nonnulls(declared_content_type, declared_byte_size) = 2 AND declared_content_type IN ('image/jpeg', 'image/png', 'image/webp') AND declared_byte_size BETWEEN 1 AND 5242880)),
    ADD CONSTRAINT ck_property_images_validated_metadata CHECK (
        num_nonnulls(content_type, byte_size, width, height) = 0 OR
        (num_nonnulls(content_type, byte_size, width, height) = 4 AND validation_state = 'VALIDATED' AND content_type IN ('image/jpeg', 'image/png')
         AND byte_size BETWEEN 1 AND 5242880 AND width > 0 AND height > 0
         AND width::bigint * height::bigint <= 20000000));
