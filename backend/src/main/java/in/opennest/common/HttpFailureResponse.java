package in.opennest.common;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;

public interface HttpFailureResponse {
    void write(HttpServletResponse response, int status) throws IOException;
}
