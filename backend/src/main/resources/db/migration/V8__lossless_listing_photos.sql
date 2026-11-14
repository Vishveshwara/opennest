ALTER TABLE public.property_images
    DROP CONSTRAINT ck_property_images_declared_metadata,
    DROP CONSTRAINT ck_property_images_validated_metadata,
    ADD CONSTRAINT ck_property_images_declared_metadata CHECK (
        num_nonnulls(declared_content_type, declared_byte_size) = 0 OR
        (num_nonnulls(declared_content_type, declared_byte_size) = 2
         AND declared_content_type IN ('image/jpeg', 'image/png', 'image/webp')
         AND declared_byte_size BETWEEN 1 AND 2147483639)),
    ADD CONSTRAINT ck_property_images_validated_metadata CHECK (
        num_nonnulls(content_type, byte_size, width, height) = 0 OR
        (num_nonnulls(content_type, byte_size, width, height) = 4
         AND validation_state = 'VALIDATED'
         AND content_type IN ('image/jpeg', 'image/png', 'image/webp')
         AND byte_size BETWEEN 1 AND 2147483639
         AND width > 0 AND height > 0
         AND (content_type <> 'image/webp' OR (width <= 16384 AND height <= 16384))));
