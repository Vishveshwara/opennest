package in.opennest.identity;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface PersonaSessions {
    /**
     * Installs an allowlisted persona inside the caller's reset-guarded transaction.
     * The caller must hold the shared reset lock while reading {@code generation}.
     */
    void select(UUID userId, long generation, HttpServletRequest request, HttpServletResponse response);
}
