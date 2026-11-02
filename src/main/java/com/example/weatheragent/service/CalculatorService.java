package com.example.weatheragent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public class CalculatorService {
    private static final Logger log = LoggerFactory.getLogger(CalculatorService.class);

    // the model supplies the numbers and operation. This method does the calculation.
    @Tool(description = "Calculate the addition, subtraction, multiplication or division of two numbers.")
    public JsonNode calculate(
            @ToolParam(description = "The first number") double firstNumber,
            @ToolParam(description = "The second number") double secondNumber,
            @ToolParam(description = "One of: add, subtract, multiply, divide") String operation) {
        log.info("Calculator tool called: {} {} {}", firstNumber, operation, secondNumber);

        if (!Double.isFinite(firstNumber) || !Double.isFinite(secondNumber)) {
            return error("Both numbers must be finite.");
        }
        if (operation == null) {
            return error("Choose add, subtract, multiply or divide.");
        }

        double result;
        switch (operation) {
            case "add" -> result = firstNumber + secondNumber;
            case "subtract" -> result = firstNumber - secondNumber;
            case "multiply" -> result = firstNumber * secondNumber;
            case "divide" -> {
                if (secondNumber == 0) {
                    return error("Cannot divide by zero.");
                }
                result = firstNumber / secondNumber;
            }
            default -> {
                return error("Choose add, subtract, multiply or divide.");
            }
        }

        if (!Double.isFinite(result)) {
            return error("The result is too large to represent.");
        }
        // Return data; the model writes the human-readable answer.
        return JsonNodeFactory.instance.objectNode().put("result", result);
    }

    private JsonNode error(String message) {
        return JsonNodeFactory.instance.objectNode().put("error", message);
    }
}
