package in.opennest.identity;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TrustBadgeTests {

    @ParameterizedTest
    @CsvSource(value = {
            "NULL,0,0,STANDARD",
            "1,1,0,STANDARD",
            "2.9999,1,0,STANDARD",
            "2.9999,2,0,LOW_RATINGS_BEWARE",
            "3,2,0,STANDARD",
            "2.9999,3,0,LOW_RATINGS_BEWARE",
            "3,3,0,STANDARD",
            "4.5,1,0,STANDARD",
            "4.5,2,0,STANDARD",
            "5,2,0,STANDARD",
            "4.4999,3,0,STANDARD",
            "4.5,3,0,TOP_RATED",
            "4.5001,3,0,TOP_RATED",
            "5,10,0,TOP_RATED",
            "1,1,1,CRITICAL_BROKER_AND_LOW_RATING",
            "2.9999,2,1,CRITICAL_BROKER_AND_LOW_RATING",
            "3,2,1,BROKERAGE_WARNED",
            "3.0001,3,1,BROKERAGE_WARNED",
            "4.5,3,1,BROKERAGE_WARNED",
            "5,10,2,BROKERAGE_WARNED"
    }, nullValues = "NULL")
    void appliesConfirmedThresholdsAndReportPrecedence(String average, long reviews, long reports, TrustBadge expected) {
        var rating = average == null ? null : new BigDecimal(average);
        assertThat(TrustBadge.calculate(rating, reviews, reports)).isEqualTo(expected);
    }
}
