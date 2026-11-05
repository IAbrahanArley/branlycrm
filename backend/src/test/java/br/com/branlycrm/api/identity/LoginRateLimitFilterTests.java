package br.com.branlycrm.api.identity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LoginRateLimitFilterTests {
    @Test void blocksRepeatedAttemptsAndExpiresWindow() {
        var filter = new LoginRateLimitFilter();
        for (int i = 0; i < 20; i++) assertThat(filter.allow("a", 1000)).isTrue();
        assertThat(filter.allow("a", 1001)).isFalse();
        assertThat(filter.allow("b", 1001)).isTrue();
        assertThat(filter.allow("a", 61000)).isTrue();
    }
}