package in.opennest.common.internal.web;

import java.io.IOException;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
final class SafeErrorController implements ErrorController {

    private final ProblemResponses responses;

    SafeErrorController(ProblemResponses responses) {
        this.responses = responses;
    }

    @RequestMapping("/error")
    void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Integer code ? code : 500;
        responses.write(response, FailureClassifier.safeStatus(status));
    }
}
