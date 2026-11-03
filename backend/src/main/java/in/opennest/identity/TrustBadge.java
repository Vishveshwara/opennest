package in.opennest.identity;

import java.math.BigDecimal;

public enum TrustBadge {
    STANDARD, TOP_RATED, LOW_RATINGS_BEWARE, BROKERAGE_WARNED, CRITICAL_BROKER_AND_LOW_RATING;

    public static TrustBadge calculate(BigDecimal average, long reviews, long reports) {
        if (reviews == 0) return STANDARD;
        if (reports >= 1) return average.compareTo(BigDecimal.valueOf(3)) < 0
                ? CRITICAL_BROKER_AND_LOW_RATING : BROKERAGE_WARNED;
        if (reviews >= 2 && average.compareTo(BigDecimal.valueOf(3)) < 0) return LOW_RATINGS_BEWARE;
        if (reviews >= 3 && average.compareTo(new BigDecimal("4.5")) >= 0) return TOP_RATED;
        return STANDARD;
    }
}
