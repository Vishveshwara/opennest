CREATE INDEX ix_auth_identities_user ON public.auth_identities (user_id);
CREATE INDEX ix_properties_owner_status ON public.properties (owner_id, status);
CREATE INDEX ix_properties_city_filters ON public.properties (city, status, category, locality, monthly_rent);
CREATE INDEX ix_properties_location ON public.properties USING gist (location);
CREATE INDEX ix_properties_newest ON public.properties (status, created_at DESC, id ASC)
    WHERE status IN ('ACTIVE', 'FULL_OCCUPIED');
CREATE INDEX ix_properties_rent_asc ON public.properties (status, monthly_rent ASC, id ASC)
    WHERE status IN ('ACTIVE', 'FULL_OCCUPIED');
CREATE INDEX ix_properties_rent_desc ON public.properties (status, monthly_rent DESC, id ASC)
    WHERE status IN ('ACTIVE', 'FULL_OCCUPIED');
CREATE INDEX ix_properties_city_newest ON public.properties (city, status, created_at DESC, id ASC)
    WHERE status IN ('ACTIVE', 'FULL_OCCUPIED');
CREATE INDEX ix_properties_city_rent_asc ON public.properties (city, status, monthly_rent ASC, id ASC)
    WHERE status IN ('ACTIVE', 'FULL_OCCUPIED');
CREATE INDEX ix_properties_city_rent_desc ON public.properties (city, status, monthly_rent DESC, id ASC)
    WHERE status IN ('ACTIVE', 'FULL_OCCUPIED');
CREATE INDEX ix_property_images_property_order ON public.property_images (property_id, display_order, id);
CREATE INDEX ix_contact_reveals_tenant_day ON public.contact_reveals (tenant_id, revealed_date, property_id);
CREATE INDEX ix_contact_reveals_property_owner ON public.contact_reveals (property_id, owner_id);
CREATE INDEX ix_owner_reviews_property_owner ON public.owner_reviews (property_id, owner_id);
CREATE INDEX ix_owner_reviews_owner ON public.owner_reviews (owner_id);
