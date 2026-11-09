package in.opennest.identity.internal.web;

import in.opennest.identity.internal.persistence.AccountProfile;
import in.opennest.identity.internal.persistence.AccountStore;
import in.opennest.identity.internal.security.SessionPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "opennest.identity.google-enabled", havingValue = "true")
class AccountController {
    private final AccountStore accounts;
    AccountController(AccountStore accounts) { this.accounts = accounts; }

    @GetMapping("/api/v1/auth/csrf")
    ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CsrfResponse(token.getToken(), token.getHeaderName()));
    }

    @GetMapping("/api/v1/users/me")
    ResponseEntity<AccountProfile> me(@AuthenticationPrincipal SessionPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.profile(principal.userId()));
    }

    record CsrfResponse(String token, String headerName) {
        @Override public String toString() { return "CsrfResponse[private]"; }
    }
}
