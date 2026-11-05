package br.com.branlycrm.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginRateLimitFilterTest {
  private final LoginRateLimitFilter filter = new LoginRateLimitFilter("/v1/auth/login");

  @Test
  void blocksRepeatedAttemptsPerAddressAndExpiresTheWindow() {
    for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
      assertThat(filter.allow("a", 1_000)).isTrue();
    }
    assertThat(filter.allow("a", 1_001)).isFalse();
    assertThat(filter.allow("b", 1_001)).isTrue();
    assertThat(filter.allow("a", 1_000 + LoginRateLimitFilter.WINDOW_MILLIS)).isTrue();
  }

  @Test
  void failsClosedWhenTooManyAddressesAreTracked() {
    for (int i = 0; i < LoginRateLimitFilter.MAX_TRACKED_ADDRESSES; i++) {
      assertThat(filter.allow("address-" + i, 1_000)).isTrue();
    }
    assertThat(filter.allow("new-address", 1_000)).isFalse();
    assertThat(filter.allow("address-0", 1_000)).isTrue();
  }

  @Test
  void answers429WithRetryAfterOnlyForLoginPosts() throws Exception {
    for (int i = 0; i < LoginRateLimitFilter.MAX_ATTEMPTS; i++) {
      assertThat(run("POST", "/v1/auth/login").getStatus()).isEqualTo(200);
    }
    MockHttpServletResponse blocked = run("POST", "/v1/auth/login");
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
    assertThat(run("GET", "/v1/auth/login").getStatus()).isEqualTo(200);
    assertThat(run("POST", "/v1/auth/logout").getStatus()).isEqualTo(200);
  }

  private MockHttpServletResponse run(String method, String path) throws Exception {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    request.setRemoteAddr("192.0.2.10");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }
}
