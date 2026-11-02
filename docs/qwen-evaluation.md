# Qwen3.7 Flash live evaluation

Date: 27 October 2026. Model: `qwen/qwen3.7-flash`, through OpenRouter.

## What changed

- Selected Qwen in `application.properties`; kept the previous Nano setting commented out.
- Commented out the Nano-specific `parallel-tool-calls=false` property.
- Updated the native OpenRouter integration tests to assert the Qwen model ID and omission of that field.
- Kept the controller, DTO, system prompt, memory advisor and tool services unchanged.

## Validation

`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./mvnw -B -ntp clean verify` passed: **61 tests, zero failures/errors**.

Then ran eight real HTTP requests against a separate packaged application on a random local port, using the configured OpenRouter and Serper credentials. Weather and search data came from the actual external services; no model or tools were mocked. Stopped that isolated process afterward. The existing IDE process on port 8092 was left running.

| Scenario | Time | Model calls | Total tokens | Observed result |
|---|---:|---:|---:|---|
| Greeting | 4.80s | 1 | 1,109 | Answered without tools. |
| Share context | 1.51s | 1 | 1,045 | Acknowledged the city without tools. |
| Recall memory | 3.32s | 1 | 1,134 | Remembered Saharanpur using the same conversation ID. |
| Weather from memory | 8.48s | 2 | 2,726 | Called weather for Saharanpur; final answer matched returned data (21°C). |
| Calculate | 3.48s | 2 | 2,263 | Called calculator; 12345 × 67 = 827,115. |
| Conversation isolation | 2.56s | 1 | 1,088 | A fresh conversation asked for the city instead of inheriting Saharanpur. |
| Conditional skip search | 14.70s | 3 | 4,576 | Fetched both cities; correct 7.6°C difference and warmer city; skipped search. Calculator-use instruction was missed. |
| Conditional run search | 23.95s | 4 | 7,654 | Called weather twice, calculator and search; correct 7.5°C difference and age 53 with source links. |

Across these eight requests: **19,130 input tokens, 2,465 output tokens, 21,595 total tokens, 15 model calls**. Median end-to-end latency: **4.14s**. These are provider-reported token totals, not a billing estimate.

## Limits observed

- All eight requests returned correct task results and finished with a final answer, usage and `Done.`. This is a small smoke test, not a measured reliability rate or a comparison against Nano.
- In the skip-search case, Qwen computed 30.3 − 22.7 itself instead of using the calculator, despite the system prompt asking it to use that tool for arithmetic. The result was correct, but strict tool-use adherence was imperfect. The other conditional case did use the calculator.
- Open-Meteo returned HTTP 503 on the first Delhi lookup in the skip-search case. Qwen requested Delhi again and recovered. The extra call was recovery from a real service failure.
- The conditional thresholds were 100°C (exercise the false branch) and −1°C (exercise the true branch for an absolute difference). Actual weather values can change between requests.
- No repeated benchmark, long-history stress test or proof of a speed/cost advantage over Nano was performed. Model decisions remain probabilistic.

## Prompts and captured output

Each block below is the actual plain text response, including tool progress and the final answer. Conversation IDs refer to the now-stopped test instance; its memory was in-process only.

### Greeting

Prompt: Hello! Reply briefly.

```text
Conversation: 2d983529-cfde-437d-853b-1da9a2a7cdba

Waiting for the model (call 1)...

Final answer:
Hello! How can I help you today?

Tokens: input 954, output 155, total 1109. Model calls: 1
Done.
```

### Share context

Prompt: My city is Saharanpur.

```text
Conversation: 5c099f91-5ae9-4e15-a8ad-cf6bff91aa8b

Waiting for the model (call 1)...

Final answer:
Got it, your city is Saharanpur.

Tokens: input 956, output 89, total 1045. Model calls: 1
Done.
```

### Recall memory

Prompt: Which city do I live in?

```text
Conversation: 5c099f91-5ae9-4e15-a8ad-cf6bff91aa8b

Waiting for the model (call 1)...

Final answer:
You live in Saharanpur.

Tokens: input 983, output 151, total 1134. Model calls: 1
Done.
```

### Weather from memory

Prompt: What is the current weather there?

```text
Conversation: 5c099f91-5ae9-4e15-a8ad-cf6bff91aa8b

Waiting for the model (call 1)...
getCurrentWeather called: city: Saharanpur
Weather in Sahāranpur: temperature 21.0°C, humidity 68%, weather code 0
Waiting for the model (call 2)...

Final answer:
It is currently clear in Saharanpur with a temperature of 21°C and humidity at 68%. This weather data is provided by Open-Meteo.

Tokens: input 2440, output 286, total 2726. Model calls: 2
Done.
```

### Calculate

Prompt: What is 12345 multiplied by 67?

```text
Conversation: a2d6c104-8432-4f02-a64a-035ea253678f

Waiting for the model (call 1)...
calculate called: firstNumber: 12345, operation: multiply, secondNumber: 67
calculate returned: result: 827115.0
Waiting for the model (call 2)...

Final answer:
12345 multiplied by 67 is 827,115.

Tokens: input 2086, output 177, total 2263. Model calls: 2
Done.
```

### Conversation isolation

Prompt: Which city do I live in?

```text
Conversation: cb657da6-fb88-4302-a32d-fee0e4812970

Waiting for the model (call 1)...

Final answer:
I don't have that information from our conversation history. Could you please tell me which city you live in?

Tokens: input 956, output 132, total 1088. Model calls: 1
Done.
```

### Conditional skip search

Prompt: Get the current weather in Mumbai and Delhi, calculate the absolute temperature difference and say which city is warmer. Only if the difference is greater than 100 degrees Celsius, search for Sachin Tendulkar's current age. Otherwise do not search.

```text
Conversation: 00bc8b71-8e9b-4b78-82e3-f4278b053963

Waiting for the model (call 1)...
getCurrentWeather called: city: Mumbai, India
Weather in Mumbai: temperature 30.3°C, humidity 40%, weather code 0
getCurrentWeather called: city: Delhi, India
getCurrentWeather failed: error: Open-Meteo returned HTTP 503. Please try again later.
Waiting for the model (call 2)...
getCurrentWeather called: city: Delhi, India
Weather in Delhi: temperature 22.7°C, humidity 71%, weather code 0
Waiting for the model (call 3)...

Final answer:
I've got the current weather for both cities:

**Mumbai**: 30.3°C (clear weather)
**Delhi**: 22.7°C (clear weather)

**Temperature Difference**: |30.3 - 22.7| = **7.6°C**

**Warmer City**: Mumbai (30.3°C is warmer than Delhi's 22.7°C)

Since the temperature difference (7.6°C) is **not greater than 100°C**, I will not search for Sachin Tendulkar's current age.

Tokens: input 4096, output 480, total 4576. Model calls: 3
Done.
```

### Conditional run search

Prompt: Get the current weather in Mumbai and Delhi, calculate the absolute temperature difference and say which city is warmer. Only if the difference is greater than -1 degrees Celsius, search for Sachin Tendulkar's current age and include a source link. Otherwise do not search.

```text
Conversation: 45a558bd-00a8-4aa3-8806-0b94f838c48e

Waiting for the model (call 1)...
getCurrentWeather called: city: Mumbai
Weather in Mumbai: temperature 30.2°C, humidity 39%, weather code 0
getCurrentWeather called: city: Delhi
Weather in Delhi: temperature 22.7°C, humidity 71%, weather code 0
Waiting for the model (call 2)...
calculate called: firstNumber: 30.2, secondNumber: 22.7, operation: subtract
calculate returned: result: 7.5
Waiting for the model (call 3)...
searchWeb called: query: Sachin Tendulkar current age 2026
searchWeb returned: results: title: Sachin Tendulkar, snippet: Sachin Ramesh Tendulkar … born 24 April 1973) is an Indian former international cricketer who captained the Indian national team., link: https://en.wikipedia.org/wiki/Sachin_Tendulkar; title: Sachin Tendulkar Celebrates 53rd Birthday with Fans at Home, snippet: Sachin Tendulkar celebrated his 53rd birthday on April 24, 2026, surrounded by the love of fans who continue to admire the man known as the God ..., link: https://www.facebook.com/Sachinist/videos/sachin-tendulkar-celebrated-his-53rd-birthday-on-april-24-2026-surrounded-by-the/2177481639669757/; title: Sachin Tendulkar Profile - Cricket Player India, snippet: Full Name. Sachin Ramesh Tendulkar ; Born. April 24, 1973, Bombay (now Mumbai), Maharashtra ; Age. 53y 185d ; Nicknames. Tendlya, Little Master ; Batting Style., link: https://www.cricinfo.com/cricketers/sachin-tendulkar-35320
Waiting for the model (call 4)...

Final answer:
## Weather Information

**Mumbai:** 30.2°C (clear weather, 39% humidity)  
**Delhi:** 22.7°C (clear weather, 71% humidity)

*Weather data sourced from Open-Meteo*

## Temperature Analysis

- **Absolute temperature difference:** |30.2 - 22.7| = **7.5°C**
- **Warmer city:** **Mumbai** (30.2°C is warmer than Delhi's 22.7°C)

Since the temperature difference (7.5°C) is greater than -1°C, I've included Sachin Tendulkar's current age below.

---

## Sachin Tendulkar's Current Age

**Age: 53 years old** (born April 24, 1973)

As of today (October 27, 2026), Sachin Tendulkar turned 53 on his most recent birthday in April 2026.

**Source links:**
- [Wikipedia - Sachin Tendulkar](https://en.wikipedia.org/wiki/Sachin_Tendulkar)
- [Facebook - Sachin Tendulkar celebrates 53rd birthday](https://www.facebook.com/Sachinist/videos/sachin-tendulkar-celebrated-his-53rd-birthday-on-april-24-2026-surrounded-by-the/2177481639669757/)
- [Cricinfo - Sachin Tendulkar Profile](https://www.cricinfo.com/cricketers/sachin-tendulkar-35320)

Tokens: input 6659, output 995, total 7654. Model calls: 4
Done.
```

Model reference: [Qwen3.7 Flash on OpenRouter](https://openrouter.ai/qwen/qwen3.7-flash).
