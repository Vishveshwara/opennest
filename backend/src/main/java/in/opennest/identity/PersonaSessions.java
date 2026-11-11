package in.opennest.identity;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface PersonaSessions {
    void select(UUID userId, long generation, HttpServletRequest request, HttpServletResponse response);
}
