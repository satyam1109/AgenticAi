package com.example.weatheragent;

import com.example.weatheragent.service.CalculatorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorServiceTests {
    private final CalculatorService calculator = new CalculatorService();

    @ParameterizedTest
    @CsvSource({
            "add, 2.5, 3.5, 6",
            "subtract, 2, 5, -3",
            "multiply, -4, 3, -12",
            "divide, 7, 2, 3.5"
    })
    void calculates(String operation, double first, double second, double expected) {
        assertThat(calculator.calculate(first, second, operation).path("result").asDouble()).isEqualTo(expected);
    }

    @Test
    void rejectsDivisionByZero() {
        assertThat(calculator.calculate(10, 0, "divide").path("error").asString())
                .isEqualTo("Cannot divide by zero.");
    }

    @Test
    void rejectsUnsupportedOperations() {
        assertThat(calculator.calculate(2, 3, "power").has("error")).isTrue();
    }

    @Test
    void rejectsNumbersAndResultsThatCannotBeRepresented() {
        assertThat(calculator.calculate(Double.NaN, 2, "add").has("error")).isTrue();
        assertThat(calculator.calculate(Double.MAX_VALUE, 2, "multiply").has("error")).isTrue();
    }
}
