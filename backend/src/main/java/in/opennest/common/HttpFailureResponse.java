package in.opennest.common;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;

public interface HttpFailureResponse {
    void write(HttpServletResponse response, int status) throws IOException;
    default void write(HttpServletResponse response, Exception failure) throws IOException { write(response, 500); }
}
