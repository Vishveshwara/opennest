package in.opennest.support;

import java.util.UUID;
import java.util.function.Consumer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

public final class PersistenceScenarioSupport {

    private PersistenceScenarioSupport() {
    }

    public static void transaction(EntityManagerFactory factory, Consumer<EntityManager> test) {
        try (var manager = factory.createEntityManager()) {
            manager.getTransaction().begin();
            try {
                test.accept(manager);
            } finally {
                if (manager.getTransaction().isActive()) {
                    manager.getTransaction().rollback();
                }
            }
        }
    }

    public static UUID user(EntityManager manager) {
        UUID id = UUID.randomUUID();
        manager.createNativeQuery("INSERT INTO public.users(id,full_name) VALUES (:id,'Fabricated persistence-test account')")
                .setParameter("id", id).executeUpdate();
        return id;
    }

    public static UUID property(EntityManager manager, UUID owner) {
        UUID id = UUID.randomUUID();
        manager.createNativeQuery("""
                INSERT INTO public.properties(id,owner_id,title,description,category,flat_rental_mode,city,locality,
                    private_address,latitude,longitude,monthly_rent,security_deposit,bedrooms,whole_flat_available,max_occupants)
                VALUES (:id,:owner,'Fabricated persistence-test flat','Test-only description','FLAT','ENTIRE_FLAT',
                    'BENGALURU','FABRICATED LOCALITY','Fabricated private address',12.9716,77.5946,10000,0,0,true,4)
                """).setParameter("id", id).setParameter("owner", owner).executeUpdate();
        return id;
    }
}
