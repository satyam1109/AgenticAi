package com.example.weatheragent;

// Previous Gemini imports, kept with the configuration below for reference.
// import com.google.genai.Client;
// import com.google.genai.types.HttpOptions;
// import com.google.genai.types.HttpRetryOptions;
// import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WeatherAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(WeatherAgentApplication.class, args);
	}

    // OpenRouter's client is now auto-configured from spring.ai.openai.* properties.
    // Previous Gemini client configuration (inactive):
    /*
    // Configure the SDK itself: Spring's retry setting does not control SDK retries.
    // Spring AI will use this client for both normal chat and streaming progress.
    @Bean(destroyMethod = "close")
    public Client googleGenAiClient(@Value("${spring.ai.google.genai.api-key}") String apiKey,
                                    @Value("${gemini.request-timeout-ms:60000}") int timeoutMs) {
        return Client.builder().apiKey(apiKey)
                .httpOptions(HttpOptions.builder().timeout(timeoutMs)
                        // One short retry for a busy provider (503), or a transport failure.
                        // Both attempts share the SDK HTTP call's timeout.
                        .retryOptions(HttpRetryOptions.builder().attempts(2).httpStatusCodes(503)
                                .initialDelay(0.5).maxDelay(0.5).jitter(0.2).build()).build())
                .build();
    }
    */

}
