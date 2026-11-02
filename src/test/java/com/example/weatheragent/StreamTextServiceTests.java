package com.example.weatheragent;

import com.example.weatheragent.dto.ChatDto;
import com.example.weatheragent.service.StreamTextService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class StreamTextServiceTests {
    private final StreamTextService text = new StreamTextService();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void weatherTextKeepsTheReturnedTemperatureAndUnits() {
        var tool = new ChatDto.ToolExecution(1, "getCurrentWeather", null, json.readTree("""
                {"location":{"name":"Delhi"},"current":{"temperature_2m":-24,"relative_humidity_2m":43,"weather_code":0},
                 "current_units":{"temperature_2m":"°C","relative_humidity_2m":"%"}}
                """), "SUCCESS", 3L);
        assertThat(text.format(event("tool_completed", tool)))
                .isEqualTo("Weather in Delhi: temperature -24°C, humidity 43%, weather code 0\n");
    }

    @Test
    void searchTextKeepsSnippetsAndSourceLinksWithoutJsonEnvelopes() {
        var tool = new ChatDto.ToolExecution(1, "searchWeb", null, json.readTree("""
                {"results":[{"title":"Profile","snippet":"Born in 1972.","link":"https://example.com/profile"}]}
                """), "SUCCESS", 3L);
        assertThat(text.format(event("tool_completed", tool))).contains("Profile", "Born in 1972.", "https://example.com/profile")
                .doesNotContain("{", "}", "event:", "\"type\"");
    }

    @Test
    void missingUsageIsClearlyUnavailableInsteadOfZero() {
        assertThat(text.format(event("usage", new ChatDto.Usage(2, null, null, null, false))))
                .contains("Tokens: unavailable", "Model calls: 2").doesNotContain("total 0");
    }

    private ChatDto.StreamEvent event(String type, Object data) {
        return new ChatDto.StreamEvent(type, "request", "chat", data);
    }
}
