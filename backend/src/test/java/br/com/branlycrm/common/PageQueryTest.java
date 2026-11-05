package br.com.branlycrm.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pagination contract of docs/api-convencoes.md: 1-based page, pageSize 1..100, default 25. */
class PageQueryTest {
  @Test
  void absentValuesUseTheDefaults() {
    assertThat(PageQuery.of(null, null)).isEqualTo(new PageQuery(1, 25));
    assertThat(PageQuery.of("", "")).isEqualTo(new PageQuery(1, PageQuery.DEFAULT_SIZE));
    assertThat(PageQuery.of(null, null).offset()).isZero();
  }

  @ParameterizedTest
  @NullAndEmptySource
  void eachAbsentValueDefaultsIndependently(String absent) {
    assertThat(PageQuery.of(absent, "10")).isEqualTo(new PageQuery(1, 10));
    assertThat(PageQuery.of("3", absent)).isEqualTo(new PageQuery(3, 25));
  }

  @Test
  void acceptsTheBoundaries() {
    assertThat(PageQuery.of("1", "1")).isEqualTo(new PageQuery(1, 1));
    assertThat(PageQuery.of("10000", "100")).isEqualTo(new PageQuery(10_000, 100));
    assertThat(PageQuery.MAX_PAGE).isEqualTo(10_000);
    assertThat(PageQuery.MAX_SIZE).isEqualTo(100);
    // Leading zeros are digits; the value is what counts.
    assertThat(PageQuery.of("000002", "025")).isEqualTo(new PageQuery(2, 25));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "10001", "999999"})
  void pageOutsideTheRangeReportsPage(String page) {
    assertInvalid(() -> PageQuery.of(page, "25"), "page");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "101", "999999"})
  void pageSizeOutsideTheRangeReportsPageSize(String pageSize) {
    assertInvalid(() -> PageQuery.of("1", pageSize), "pageSize");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "-1",
        "+1",
        "1.5",
        "abc",
        " 1",
        "1 ",
        "1e3",
        "0x10",
        "1,2",
        "\u0661",
        "0000001",
        "2147483648",
        "99999999999999999999999999999999"
      })
  void nonDigitsOrOverlongValuesAreRejectedWithTheRightField(String raw) {
    assertInvalid(() -> PageQuery.of(raw, "25"), "page");
    assertInvalid(() -> PageQuery.of("1", raw), "pageSize");
  }

  @Test
  void thePageIsCheckedBeforeThePageSize() {
    assertInvalid(() -> PageQuery.of("0", "0"), "page");
  }

  @Test
  void constructorEnforcesTheSameInvariants() {
    assertInvalid(() -> new PageQuery(0, 25), "page");
    assertInvalid(() -> new PageQuery(-1, 25), "page");
    assertInvalid(() -> new PageQuery(10_001, 25), "page");
    assertInvalid(() -> new PageQuery(1, 0), "pageSize");
    assertInvalid(() -> new PageQuery(1, 101), "pageSize");
  }

  @Test
  void offsetSkipsThePreviousPages() {
    assertThat(new PageQuery(1, 25).offset()).isZero();
    assertThat(new PageQuery(2, 25).offset()).isEqualTo(25);
    assertThat(new PageQuery(3, 10).offset()).isEqualTo(20);
    // Largest allowed offset stays far inside an int.
    assertThat(new PageQuery(10_000, 100).offset()).isEqualTo(999_900);
  }

  @Test
  void exceptionNamesTheFieldNeverTheRejectedValue() {
    assertThatThrownBy(() -> PageQuery.of("secret-looking-value", "25"))
        .isInstanceOfSatisfying(
            InvalidInputException.class,
            invalid -> {
              assertThat(invalid.fields()).containsExactly("page");
              assertThat(invalid.getMessage()).doesNotContain("secret-looking-value");
            });
  }

  private static void assertInvalid(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            InvalidInputException.class,
            invalid -> assertThat(invalid.fields()).containsExactly(field));
  }
}
