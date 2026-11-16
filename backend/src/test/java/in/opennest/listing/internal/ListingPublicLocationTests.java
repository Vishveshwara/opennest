package in.opennest.listing.internal;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import in.opennest.common.SandboxBoundary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ListingPublicLocationTests {
    @ParameterizedTest
    @CsvSource({"12.959999,12.95", "12.960000,12.97", "12.960001,12.97", "6.5,6.51", "37.5,37.51", "68,68.01", "97.5,97.51"})
    void decimalGridUsesCellCentersAcrossBoundariesAndSupportedBounds(String coordinate, String expected) {
        assertThat(ListingStore.coarsenedCoordinate(new BigDecimal(coordinate))).isEqualByComparingTo(expected);
    }

    @Test void everyStoredCoordinateInTheSameCellHasTheSamePublicLocation() {
        for (int increment = 0; increment < 20000; increment++) {
            var value = new BigDecimal("12.96").add(BigDecimal.valueOf(increment, 6));
            assertThat(ListingStore.coarsenedCoordinate(value)).isEqualByComparingTo("12.97");
        }
    }

    @Test void responseExposesOnlyCoarsenedCoordinatesPubliclyAndPreservesOwnerOnlyFields() {
        var store = new ListingStore(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class), mock(SandboxBoundary.class));
        var media = mock(ListingMedia.class);
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.randomUUID());
        row.put("category", "FLAT");
        row.put("flat_rental_mode", "ENTIRE_FLAT");
        row.put("latitude", new BigDecimal("12.971234"));
        row.put("longitude", new BigDecimal("77.594321"));
        row.put("private_address", "Fabricated private draft address");
        row.put("version", 3L);
        row.put("created_at", Timestamp.from(Instant.parse("2026-11-11T00:00:00Z")));
        row.put("updated_at", row.get("created_at"));
        row.put("review_count", 0L);
        row.put("broker_report_count", 0L);

        var publicResponse = store.response(row, false, List.of(), media);
        var ownerResponse = store.response(row, true, List.of(), media);

        assertThat(publicResponse).doesNotContainKeys("latitude", "longitude", "privateAddress", "version", "distance", "distanceMeters");
        assertThat(publicResponse.get("publicLocation")).isEqualTo(Map.of("latitude", new BigDecimal("12.97"),
                "longitude", new BigDecimal("77.59"), "radiusMeters", 2000));
        assertThat(ownerResponse.get("publicLocation")).isEqualTo(publicResponse.get("publicLocation"));
        assertThat(ownerResponse).containsEntry("latitude", row.get("latitude")).containsEntry("longitude", row.get("longitude"))
                .containsEntry("privateAddress", row.get("private_address")).containsEntry("version", 3L);
    }
}
