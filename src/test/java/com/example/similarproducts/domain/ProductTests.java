package com.example.similarproducts.domain;

import java.math.BigDecimal;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ProductTests {

    @Test
    void retainsRequiredFieldsIncludingUnavailableProducts() {
        var price = new BigDecimal("29.99");

        var product = new Product("3", "Blazer", price, false);

        assertThat(product.id()).isEqualTo("3");
        assertThat(product.name()).isEqualTo("Blazer");
        assertThat(product.price()).isEqualByComparingTo(price);
        assertThat(product.availability()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("nullRequiredFields")
    void rejectsNullRequiredFields(String id, String name, BigDecimal price, String field) {
        assertThatNullPointerException()
                .isThrownBy(() -> new Product(id, name, price, true))
                .withMessage(field + " must not be null");
    }

    static Stream<Arguments> nullRequiredFields() {
        return Stream.of(
                Arguments.of(null, "Shirt", BigDecimal.ONE, "id"),
                Arguments.of("1", null, BigDecimal.ONE, "name"),
                Arguments.of("1", "Shirt", null, "price"));
    }

    @ParameterizedTest
    @MethodSource("emptyRequiredStrings")
    void rejectsEmptyIdOrName(String id, String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Product(id, name, BigDecimal.ONE, true));
    }

    static Stream<Arguments> emptyRequiredStrings() {
        return Stream.of(Arguments.of("", "Shirt"), Arguments.of("1", ""));
    }

    @Test
    void acceptsWhitespaceWithoutAddingAnUnspecifiedNonblankRule() {
        var product = new Product(" ", " ", BigDecimal.ONE, true);

        assertThat(product.id()).isEqualTo(" ");
        assertThat(product.name()).isEqualTo(" ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01"})
    void acceptsPricesWithoutAnUnspecifiedPositivityRule(String value) {
        var product = new Product("1", "Shirt", new BigDecimal(value), true);

        assertThat(product.price().toPlainString()).isEqualTo(value);
    }

    @Test
    void preservesDecimalPrecisionAndScaleWithoutRounding() {
        String value = "12345678901234567890.123456789012345678901234567890";

        var product = new Product("1", "Shirt", new BigDecimal(value), true);

        assertThat(product.price().toPlainString()).isEqualTo(value);
        assertThat(product.price().scale()).isEqualTo(30);
    }
}
