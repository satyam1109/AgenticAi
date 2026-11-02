# Spring AI agent: Qwen3.7 Flash through OpenRouter

The flow is:

```text
POST /api/chat
    -> ChatController reads the message and creates/reuses a conversationId
    -> LlmService.chat(userPrompt, conversationId)
    -> Memory advisor loads previous messages for that conversation
    -> ChatClient sends the messages and available tool descriptions to the model
    -> If weather is needed, Spring AI calls WeatherService.getCurrentWeather(city)
    -> If arithmetic is needed, Spring AI calls CalculatorService.calculate(firstNumber, secondNumber, operation)
    -> If current facts are needed, Spring AI calls WebSearchService.searchWeb(query)
    -> If products or shopping prices are needed, Spring AI calls ShoppingService.searchProducts(query, maxPrice)
    -> For file checks, Spring AI calls getFileInfo or listDirectory
    -> For partial edits, Spring AI calls searchInFile/readFileLines and replaceText
    -> For creation or whole-file work, Spring AI calls createDirectory/createFile/readFile/updateFile
    -> Spring AI sends the tool result back to the model
    -> the model returns an answer
    -> Memory advisor saves the user message and final answer (large filesystem replies use compact outcome records)
    -> Controller returns {"answer":"...", "conversationId":"..."}
```

## Code to understand first

The model call stays in `LlmService.callModel()`. Its essential flow is:

```java
private String callModel(String userPrompt, String conversationId) {
    var response = chatClient.prompt()
            .system(SYSTEM_PROMPT + "\nToday's date is " + LocalDate.now() + ".")
            .user(userPrompt)
            .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
            .tools(weatherService, calculatorService, webSearchService, shoppingService, fileSystemService)
            .call()
            .chatResponse();
    // The actual method also checks that an answer was returned.
    return response.getResult().getOutput().getText();
}
```

`SYSTEM_PROMPT` tells the model it is a task assistant with weather, calculator, search, shopping and filesystem tools.
`.tools(weatherService, calculatorService, webSearchService, shoppingService, fileSystemService)` exposes the annotated methods to the model. Spring AI
handles the tool call and sends the result back automatically; we do not build JSON
or write an agent loop ourselves. Ordinary questions still receive normal chat replies.

The weather tool is defined in one class:

```java
@Tool(description = "Get current temperature, humidity and weather code when the user requests current weather. Merely stating a city is not a weather request. Not a future forecast.")
public JsonNode getCurrentWeather(
        @ToolParam(description = "City name, optionally followed by country, e.g. Mumbai, India") String city) {
    // Return weather data as JSON; the model writes the final answer.
}
```

- `@Tool` tells the model what the method does.
- `@ToolParam` explains the method's input so the model can supply the city.
- `WeatherService` first calls Open-Meteo's geocoding endpoint to find the city's
  latitude and longitude. It then uses those coordinates to fetch current weather.
  It returns `location`, `current`, `current_units` and `timezone` as JSON.
  the model turns that data into a readable answer. Tool failures return an `error` field.
  The console log `Open-Meteo JSON received` shows the weather response.
- `CalculatorService.calculate(firstNumber, secondNumber, operation)` accepts `add`,
  `subtract`, `multiply` or `divide` and returns JSON such as `{"result":168.0}`.
  Invalid operations and division by zero return an `error` field. The console log
  `Calculator tool called` confirms that the model requested a calculation.
  It uses Java `double` arithmetic, which can round decimal values.
- `WebSearchService.searchWeb(query)` sends a normal HTTP POST to Serper's Google
  search endpoint. It sends the search query as JSON and the Serper key in the
  `X-API-KEY` header. It returns up to three titles, snippets and source links.
  This service makes no LLM calls. Returning only these fields keeps the main
  agent's input small. The main model calls still use tokens.
  The log `Web search tool called` confirms invocation; `Serper returned ... search results`
  confirms that the search returned data. Missing keys, empty results and provider
  errors return an `error` field.

For example, asking someone's current age follows this flow:

```text
User question -> LlmService -> the model requests searchWeb
    -> WebSearchService -> Serper HTTP API -> Google search results
    -> Titles, snippets and links -> the model response with source links
```

Today's date is included in the main agent's system prompt for age calculations.
Search snippets may be incomplete; the agent should not invent missing facts.
The prompt also distinguishes sharing context from requesting a task:
"My city is Saharanpur" should receive a brief acknowledgement, while "What is
the weather there?" requests the weather tool. Supplying a city after the assistant
asks for one continues the pending weather request. These are model instructions;
tool selection is still decided by the model.

The controller reads the prompt and calls the service:

```java
String userPrompt = request.message();
String answer = llmService.chat(userPrompt, conversationId);
return new ChatDto.Response(answer, conversationId);
```

## Files

```text
src/main/java/com/example/weatheragent/
├── WeatherAgentApplication.java   # Starts Spring Boot; previous Gemini client kept commented out
├── controller/ChatController.java # JSON endpoint and plain text progress stream
├── service/LlmService.java        # System prompt, memory advisor, tools and call()
├── service/AgentTraceService.java # Observes model/tool calls, emits events, sums tokens
├── service/FileAnswerMemory.java # Compacts large final filesystem replies only when saving next-turn memory
├── service/StreamTextService.java # Formats progress and tool results as plain text
├── service/WeatherService.java    # @Tool method that fetches current weather
├── service/CalculatorService.java # @Tool method for arithmetic
├── service/WebSearchService.java  # @Tool method for Google Search
├── service/ShoppingService.java   # @Tool method for Indian product listings and INR budget filtering
├── service/FileSystemService.java # Nine @Tool methods for metadata, listing, snippets, targeted edits and whole-file operations
└── dto/ChatDto.java               # Request, response and stream event records

src/main/resources/application.properties  # OpenRouter/model settings
```

The OpenAI starter configures `OpenAiChatModel` using `application.properties`.
Its OpenAI-compatible requests go to OpenRouter, which routes them to
`qwen/qwen3.7-flash`. Spring injects a `ChatClient.Builder`, `ChatMemory` and the
five tool services into `LlmService`. The old Gemini dependency, properties,
client bean and SDK tests are commented out for reference.

`LlmService` attaches Spring AI's built-in memory advisor when constructing the client:

```java
var memoryForNextTurn = new FileAnswerMemory(chatMemory, traces::fileResultsForMemory);
this.chatClient = builder
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(memoryForNextTurn).build())
        .build();
```

- `ChatMemory` is Spring AI's interface for conversation context.
- Its default `MessageWindowChatMemory` keeps up to 20 messages per conversation,
  using an in-memory repository. This means messages, not 20 complete exchanges.
- `MessageChatMemoryAdvisor` loads context before a call and stores the new exchange
  afterward. The application does not maintain its own message list.
- `FileAnswerMemory` delegates to the same store and projects only a large final
  assistant reply from an actual filesystem task. User messages are unchanged.
- `conversationId` selects which conversation's memory is used. Omitting it starts
  a new chat and returns a generated ID. Reuse that ID to continue the chat.

Memory resets when the app restarts and is separate in each running app instance.
Older context falls out of the message window. Previous messages count toward input
tokens. Send follow-up requests one at a time within a conversation.
When persistence is needed, a JDBC memory repository can replace the in-memory
repository without replacing the advisor. A complete audit history is a separate concern.

### Keeping generated code out of the next user turn

There are two distinct histories. With the memory advisor outside the tool loop,
intermediate tool calls/results are not normally saved to conversation memory;
the streamed `createFile called: content: ...` output is a progress event, not the
final assistant memory entry. However, the model can repeat complete code in its
final answer, and that final answer would otherwise be stored verbatim.

`FileAnswerMemory` handles that case at the storage boundary. When the current
request actually executed a filesystem tool and its final assistant text exceeds
**2000 characters or 40 lines**, it stores an application-generated record of
the latest file-tool outcomes instead. That record includes operations, paths,
success/verification status and replacement counts where available. It does not
include file content, generated arguments, search excerpts or the original large
final reply. At most the latest 10 outcomes are retained, and the record states
how many earlier outcomes were omitted. Failure and unverified-write flags are
preserved rather than converted into successful completion claims.

```text
Model/tool loop: unchanged, including full generated createFile arguments
Visible final answer: unchanged, even if it repeats the full HTML/CSS
Saved user message: unchanged
Saved assistant entry for a large file-task reply: compact filesystem outcomes
Next user request: sees those outcomes; can search/read files again if needed
```

The rule is deterministic Java code, with no extra model call or attempt to
classify programming languages. Short final answers remain verbatim. Long
non-filesystem chat replies remain verbatim. Consequently, this is not a global
filter for every code-containing answer: a request with no filesystem-tool
execution is outside this rule. Any oversized final reply on a filesystem turn
is compacted, including long prose or answers to other questions in that same
turn; those details remain in the original user-visible response but not the
compact memory entry. The record describes past tool outcomes, not a new check
of the current on-disk state. It does not retroactively rewrite old memory.

`AgentTraceService` collects only the required result metadata per request and
conversation, using the existing observations. `FileAnswerMemory` copies the
assistant message for storage without mutating the original response. The
`memory=COMPACT_FILE_REPLY` log shows when the rule runs and the original/stored
character counts. The underlying message-window size and repository are unchanged.
Nothing changes the model's current tool loop, tool selection, current response,
or token accounting. Tokens used to generate the code initially are still used.

Weather currently supports one city's current conditions, not future forecasts.
The first matching city is used, so include a country when a city name is ambiguous.

To add another task later, create another `@Tool` method and include its service in
`.tools(weatherService, calculatorService, webSearchService, shoppingService, fileSystemService, anotherService)`.
The controller and DTO can stay as they are.

## Watch the agent work

`POST /api/chat` returns the usual JSON response. `POST /api/chat/stream` now
returns a **plain text stream**, using the same request body:

```bash
curl -N http://localhost:8092/api/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/plain' \
  -d '{"message":"What is the current weather in Mumbai?"}'
```

Example output (values are illustrative):

```text
Conversation: a-generated-conversation-id

Waiting for the model (call 1)...
getCurrentWeather called: city: Mumbai
Weather in Mumbai: temperature 24°C, humidity 70%, weather code 0
Waiting for the model (call 2)...

Final answer:
Mumbai is currently 24°C with clear skies, according to Open-Meteo.

Tokens: input 1200, output 50, total 1250. Model calls: 2
Done.
```

In Postman, send the JSON body as before. The response is `text/plain;charset=UTF-8`,
so it is displayed as text instead of SSE event cards. Remove an old
`Accept: text/event-stream` header or replace it with `Accept: text/plain`.
`curl -N` disables client buffering so lines appear as soon as the server sends them.
This is a POST endpoint; simply opening its URL in a browser does not start a chat.

Reuse the conversation ID in the next request to continue memory. Both
`X-Conversation-Id` and `X-Request-Id` are also included in response headers.
Tool results and the waiting messages appear live. The complete final answer is
sent after the model returns it; this version does not stream individual answer tokens.
The text formatter uses the returned weather units and preserves search snippets
and source links. It performs no additional LLM calls.

How it works internally:

1. `ChatController` creates a Spring MVC `ResponseBodyEmitter` and runs the chat on
   Spring's application task executor.
2. `LlmService` uses the same system prompt, ChatMemory advisor and registered tools.
   Spring AI continues running the model/tool loop.
3. `AgentTraceService` observes model calls and tool executions, tracking each
   request separately. `StreamTextService` formats this progress as readable text.
4. The final answer, usage summary and `Done.` finish the response.

The Actuator dependency enables observations. Logs include model start/completion,
request IDs, tool durations and provider-reported usage. If tools have completed
but the next model call has not, the application is waiting for the model to return.
The stream does not ask the model to make any extra calls for progress reporting.

Spring AI creates the OpenAI client automatically from these properties:

```properties
spring.config.import=optional:file:./secret.properties
spring.ai.model.chat=openai
spring.ai.model.embedding=none
spring.ai.openai.base-url=https://openrouter.ai/api/v1
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.model=qwen/qwen3.7-flash
spring.ai.openai.timeout=60s
spring.ai.openai.max-retries=1
chat.stream.timeout-ms=120000
```

The base URL includes `/api/v1`; the SDK adds `/chat/completions`. Use your
OpenRouter key for authentication. The model setting selects Qwen3.7 Flash. The previous Nano model and its
`parallel-tool-calls=false` setting remain commented out in the properties file.
Qwen supports tool calling; we omit that Nano-specific setting. Spring AI handles
the tool loop through the same OpenAI-compatible connection.

`timeout=60s` limits an HTTP attempt and `max-retries=1` permits at most one SDK
retry for eligible failures. Model rounds can therefore take more than 60 seconds.
The stream has a separate two-minute overall limit. The old `gemini.request-timeout-ms`
property and custom Gemini retry configuration are commented out and inactive.
These settings bound waiting but do not guarantee upstream provider availability.

Token totals include every model round once, including tool selection and the
final answer. We do not add Spring AI's already-accumulated final usage again.
`modelCalls` counts logical ChatModel invocations, not tool executions. History
sent to the model is counted as input again. Provider totals can include thinking
tokens, so the total may exceed input plus output. Missing usage is displayed as
`Tokens: unavailable`, rather than a misleading zero. These are per-request
provider usage reports, not a billing audit.

Tool errors are printed and the model can explain them in its final answer. Model
failures print `Error: ...`, available usage information, and `Done.`. HTTP 200
only means the stream started; check its text for the actual outcome. Blank input
still returns HTTP 400 before the stream starts. Logs show `failed causes=... reason=...`
without printing raw exception messages that might include credentials or prompts.

An overall stream timeout closes the connection and cancels its worker. Spring
no longer permits writes from the emitter's timeout callback. Treat a response
ending without `Done.` as interrupted; final usage may be unavailable. Disconnects
are detected on writes or error callbacks. In-flight requests may take time to stop.

## Run

Use Java 21 or newer. Keep local API keys in `secret.properties` at the project
root, alongside `pom.xml`:

```properties
# OPENAI_API_KEY contains the OpenRouter key for the active model connection.
OPENAI_API_KEY=your-openrouter-key
SERPER_API_KEY=your-serper-key
GEMINI_API_KEY=your-gemini-key
```

Values have no surrounding quotes; quotes would become part of the key. Gemini
is inactive, so its key is retained only for that previous integration.
`application.properties` imports the file with
`spring.config.import=optional:file:./secret.properties` and references these
variables. `secret.properties` is ignored by Git and is outside the packaged JAR.
Run from the project root so the relative import resolves correctly:

```bash
./mvnw spring-boot:run
```

For IntelliJ, set the Run Configuration's working directory to the project root.
Environment variables named `OPENAI_API_KEY` and `SERPER_API_KEY` can override
the corresponding values in the file. A fresh checkout needs its own ignored
`secret.properties` file or those environment variables. Spring Boot does not
automatically read `.env` files.

The configured model is `qwen/qwen3.7-flash`. To switch back to Nano, restore its
commented model line and parallel-tool setting, then restart the app. The previous Gemini configuration
remains commented out; uncommenting only its properties is not enough to switch
back because its dependency, client code and provider-specific usage handling
would also need restoring.

The Open-Meteo weather endpoints do not need a key for this learning project.
These URLs are already set in `application.properties`:

```properties
weather.geocoding-url=https://geocoding-api.open-meteo.com/v1/search
weather.forecast-url=https://api.open-meteo.com/v1/forecast
```

Restart the application after changing properties.

In another terminal:

```bash
curl http://127.0.0.1:8092/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is the current weather in Mumbai, India?"}'
```

Response shape:

```json
{"answer":"The response generated by the model.","conversationId":"a-generated-id"}
```

To test memory, first send:

```json
{"message":"My city is Saharanpur."}
```

Copy the `conversationId` from the response and send a follow-up:

```json
{"message":"What is the current weather there?","conversationId":"paste-the-returned-id-here"}
```

The earlier city is now included in the model's context, so it can request the weather
tool for Saharanpur. A different ID starts separate context.

For a calculation, use the same endpoint and request format:

```json
{"message":"What is 24 multiplied by 7?"}
```

For web search:

```json
{"message":"What is Sachin Tendulkar's current age? Include your sources."}
```

Set your Serper key in `secret.properties`, then restart:

```properties
SERPER_API_KEY=your-serper-key
```

`application.properties` already uses `serper.api.key=${SERPER_API_KEY:}`.
Alternatively, set `SERPER_API_KEY` in the environment of the application process.
Serper credits are separate from the model
tokens and quota. HTTP 401/403 indicates an authentication/access problem; HTTP 429
indicates rate limiting or a quota problem. Check your Serper dashboard for details.

## Shopping queries

Use the existing `POST /api/chat` or `POST /api/chat/stream` endpoint:

```json
{"message":"Find five laptops within ₹50,000 in India. Include listed prices, sellers and links."}
```

`LlmService` registers `ShoppingService` alongside the other tools. The system
prompt directs product searches to its `searchProducts(query, maxPrice)` method.
For this request, the model supplies a product query and `maxPrice=50000`.
`@ToolParam(required=false)` makes the budget optional: a request such as
"Find wireless mice in India" can omit it.

The service sends `POST https://google.serper.dev/shopping`, using the existing
`serper.api.key` and Indian search settings (`gl=in`, `hl=en`). The endpoint is
configured separately:

```properties
serper.shopping.url=https://google.serper.dev/shopping
```

Java reads clearly labelled INR prices and applies the maximum budget **inclusively**
(a ₹50,000 listing fits a ₹50,000 maximum). It returns up to five matching listings
in search order, with `title`, the original `price`, numeric `priceInr`, `source`
and `link`. It skips foreign currency, unclear prices, price ranges, monthly
instalment quotes, zero prices and listings missing a title or link.
`BigDecimal` performs the price comparison without binary floating-point rounding.
Only the compact product data goes back to the model; this service makes no LLM call.

The LLM explains the returned listings and should not invent specifications that
are absent from the data. Prices, specifications and stock are not checkout-verified,
and links may lead through Google Shopping. These are matching search results,
not an exhaustive catalogue or a guarantee of the best offer. An empty `products`
array means no clear matching INR prices were found in the returned listings.

The existing plain text stream automatically prints `searchProducts called:`,
the returned product fields, the final answer and token usage. No controller or
DTO change is needed. Restart the application after adding the new service.

## Filesystem queries

`FileSystemService` exposes nine tools, using Java's built-in `Path` and `Files` APIs:

| Tool | Behaviour |
|---|---|
| `listDirectory(path, offset)` | Lists immediate files and subdirectories by name and type; returns no file contents. Omit the optional offset for the first page. |
| `createDirectory(path)` | Creates the directory and missing parents; an existing directory is accepted. |
| `createFile(path, content)` | Creates a new UTF-8 text file and any missing parent directories; refuses to overwrite an existing file. |
| `getFileInfo(path)` | Returns type, byte size, modification time and a readability check; does not read or return file content. |
| `searchInFile(path, searchText, startLine)` | Finds case-sensitive literal text and returns nearby context for up to 5 matching lines. Each excerpt is limited to 1000 characters. Omit the starting line for line 1. |
| `readFileLines(path, startLine, endLine)` | Returns an inclusive range of up to 40 lines and 6000 characters, preserving exact line endings. |
| `replaceText(path, oldText, newText, expectedVersion, expectedOccurrences)` | Applies small exact replacements locally, checks the version and occurrence count, and verifies the saved result. Omit the count for 1. |
| `readFile(path)` | Returns the complete existing text file and its version; reserved for requests that need the whole file. |
| `updateFile(path, content)` | Replaces an existing text file's complete contents; reserved for explicitly requested full rewrites. Refuses to create a missing file. |

The file path includes the filename and extension. Any text extension is supported,
including `.html`, `.js`, `.txt`, `.css`, `.json` and `.java`. Empty text is valid.
Reads, new content and files being updated are limited to **100 KiB of UTF-8 bytes**.
Metadata checks and directory listings do not read file contents and can describe
larger files or binary files without loading them into the model's context.
Invalid UTF-8, binary content containing NUL bytes, directory reads and symbolic-link
files are rejected. There is no delete or code-execution tool.

The default directory is configured for this checkout:

```properties
agent.files.base-path=/Users/satyamsingh/Desktop/projects/test_agent
```

This directory must already exist. Change the property if the checkout moves.
`website/index.html` resolves under it. `Desktop/test_agent_1` and
`~/Desktop/test_agent_1` resolve under the home directory of the OS account
running the application (`user.home`); no username or terminal access is needed.
These aliases work with all nine tools. `~` refers to that account's home itself.
An explicit absolute path such as `/Users/satyamsingh/Desktop/website/index.html`
targets that location instead. Other relative paths cannot escape through `..`
or an existing symbolic link outside the default directory. Home/Desktop aliases
are expanded into absolute paths. The system prompt instructs the model to use
outside locations only when the user specifies them.

Listing is one level at a time: the model can list a returned subdirectory in a
subsequent call when the user requests further browsing. The tool returns at
most 100 entries sorted by path, including hidden entries, with `count`, `offset`
and `hasMore`. If `hasMore` is true, it also returns `nextOffset`; use that value
in the next call to retrieve the remaining entries. Directory changes between
pages can change their positions. Entry types are `directory`, `file`,
`symbolic_link` or `other`. A symbolic-link entry is reported without following
it; the directory being listed must itself be a directory, not a symbolic link.
Missing directories and OS permission errors return failures; listing never
creates anything. Access is still subject to the application's OS permissions.
On a remote server, `user.home` means the server account, not the browser user's
computer. This implementation uses the conventional `user.home/Desktop` path;
a relocated Desktop needs its actual absolute path.

Send a normal request to `POST /api/chat` or `POST /api/chat/stream`:

```json
{"message":"Create a directory called website and an index.html inside it containing a welcome heading."}
```

To browse your Desktop folder:

```json
{"message":"What folders are inside Desktop/test_agent_1?"}
```

Then, using the returned conversation ID, you can ask:

```json
{"message":"Read website/index.html and change only the heading to Hello from my agent. Keep everything else.","conversationId":"paste-the-returned-id-here"}
```

The model requests the tools; Spring AI executes them and sends their structured
results back. Each result includes `success`, `operation` and `path`. Reads also
include `content`; verified writes include `bytesWritten` and `verified: true`;
failures include `error`.
After each successful write, Java automatically reopens the file and compares its
saved UTF-8 bytes with the expected bytes (supplied for a full write, constructed
locally for a targeted edit). This uses the same bounded
read and file-type checks as `readFile`, with no extra LLM call or separate
`readFile` tool event. The stream includes `verified: true` in the write result,
and the log `step=WRITE_VERIFIED` records the check.
If the read-back fails or the content differs, the result instead contains
`success: false`, `verified: false`, `writeCompleted: true` and
`errorCode: VERIFICATION_FAILED`. The file may already have changed; there is no
automatic rollback or write retry. Verification confirms the saved tool input
at that point in time; it does not validate the model's interpretation of the
user's request or provide a completion gate for skipped tools.
For a partial edit, the model is instructed to search/read a small section and
send only the replacement block. The existing plain text stream shows these operations
and the final answer automatically. Files persist on disk after an app restart;
conversation memory still resets because it is in memory.

### Small file checks and edits

For "Can you access style.css?", the intended tool is `getFileInfo`. It returns
metadata, without the CSS content. `readable` is an OS readability check, not a
guarantee that the file is supported UTF-8 text or smaller than the edit limit.

For "Change the primary button background to red", the intended flow is:

```text
searchInFile("style.css", ".btn-primary", null)
    -> matching line numbers, nearby context and file version
readFileLines(...) if more local context is necessary
replaceText(path, exactOldBlock, newBlock, version, 1)
    -> Java checks the version and exact occurrence count
    -> Java replaces only that block, preserving unrelated content
    -> Java saves and reads back the complete expected result
    -> small JSON result: verified status, replacement count and new version
model -> final answer
```

Search returns matching **lines**, with up to two lines on each side. Long
contexts are clipped around the match and marked `contextTruncated: true`;
these excerpts are not complete line ranges. If `hasMore` is true, pass
`nextStartLine` on the next search to inspect the remaining matching lines.
Search is literal and case-sensitive, not a regular expression. Line-range
reads reject oversized responses so the model can request fewer lines instead.

`searchInFile`, `readFileLines` and the complete `readFile` return a SHA-256
`version` of the contents inspected. `replaceText` requires that version. A
changed or missing version, missing file, missing old text, or wrong occurrence
count fails before writing. By default, the old text must occur exactly once.
Use a unique surrounding block to distinguish repeated CSS values. To replace
a name in multiple places when requested, inspect all matches and explicitly
supply the expected number of occurrences. Both replacement blocks are limited
to 6000 characters. Matching is exact, including whitespace and line endings;
neither regex replacement nor blind line-number replacement is used.

Java still reads the full file locally to search, construct the edit, and verify
the saved bytes. Those local reads consume no LLM tokens. A successful edit
returns no full-file content; it adds `replacements` and the new `version` to the
usual verified-write metadata. A verification failure does not claim successful
replacement. Version checks detect stale snapshots before writing, but do not
lock out another process writing to the file at the same time.

These tools reduce file data sent to the model and code generated in tool
arguments. The system prompt directs the model to use them for partial edits;
the whole-file tools remain available for genuine whole-file work. This is not
a policy gate that makes whole-file calls impossible. No LLM-based conversation
summarization, within-task tool-history compaction or provider caching was implemented here.
Large final filesystem replies are projected only when saving next-turn memory,
as described in the memory section.
Spring AI still forwards previous tool exchanges within the same request, so
small excerpts and edits can still be repeated in subsequent model rounds.

### Following a filesystem call in the logs

Detailed filesystem logging is enabled in `application.properties`:

```properties
logging.level.com.example.weatheragent.service.FileSystemService=DEBUG
```

Restart the running application after rebuilding to use these logs. Send your
normal chat request, then look in the application's terminal or IDE console.
Each Java tool invocation gets its own `fileCallId`. Filter on that ID to follow
one operation even if several operations are interleaved.

The execution flow is:

```text
User message -> LlmService -> model requests a named tool with JSON arguments
             -> Spring AI maps those arguments to the matching @Tool method
             -> FileSystemService executes Java filesystem operations
             -> returned JSON goes back to the model through Spring AI
             -> model requests another tool or provides the final answer
```

The model's request is a function name and arguments, not a direct Java call.
For example, `createFile` with arguments
`{"path":"website/hello.txt","content":"Hello everyone"}` is mapped to
`createFile("website/hello.txt", "Hello everyone")`. The nine methods return:

| Method | Successful JSON result |
|---|---|
| `listDirectory` | `success`, `operation`, absolute `path`, `entries` (names/types), `offset`, `count`, `hasMore`, and `nextOffset` when another page exists |
| `createDirectory` | `success`, `operation`, absolute `path` |
| `createFile` | `success`, `operation`, absolute `path`, `bytesWritten`, `verified` |
| `getFileInfo` | `success`, `operation`, absolute `path`, `type`, `sizeBytes`, `readable`, `lastModified` |
| `searchInFile` | `success`, `operation`, absolute `path`, `version`, `totalLines`, bounded `matches`, `count`, `hasMore`, optional `nextStartLine` |
| `readFileLines` | `success`, `operation`, absolute `path`, `version`, `totalLines`, `startLine`, `endLine`, selected `content` |
| `replaceText` | `success`, `operation`, absolute `path`, `bytesWritten`, `verified`, `replacements`, new `version` |
| `readFile` | `success`, `operation`, absolute `path`, `version`, full file `content` |
| `updateFile` | `success`, `operation`, absolute `path`, `bytesWritten`, `verified` |

Ordinary failures return `success: false`, `operation`, the supplied `path` and
`error`. A failed post-write verification has the additional fields described
above. `step=RESULT` logs the complete, final JSON returned by the method; it is
logged after all fields have been added. The model's natural-language final
answer is separate from this tool result.

For example, a successful create-file call produces entries in this order
(timestamps, logger names and some validation entries omitted):

```text
fileCallId=abc step=CALLED operation=createFile path="website/hello.txt"
fileCallId=abc step=ARGUMENTS arguments={"path":"website/hello.txt","content":"Hello everyone"}
fileCallId=abc step=VALIDATE_WRITE_CONTENT characters=14 maxBytes=102400
fileCallId=abc step=WRITE_CONTENT_ACCEPTED utf8Bytes=14
fileCallId=abc step=RESOLVE_PATH mode=RELATIVE basePath=/.../test_agent resolvedPath=/.../test_agent/website/hello.txt
fileCallId=abc step=PATH_ACCEPTED path=/.../test_agent/website/hello.txt
fileCallId=abc step=ENSURE_PARENT_DIRECTORY path=/.../test_agent/website
fileCallId=abc step=WRITE path=/.../test_agent/website/hello.txt mode=CREATE_NEW bytes=14
fileCallId=abc step=WRITE_COMPLETED path=/.../test_agent/website/hello.txt
fileCallId=abc step=VERIFY_WRITE_START path=/.../test_agent/website/hello.txt expectedBytes=14
fileCallId=abc step=READ_CHECK purpose=WRITE_VERIFICATION path=/.../test_agent/website/hello.txt exists=true
fileCallId=abc step=READ_START purpose=WRITE_VERIFICATION path=/.../test_agent/website/hello.txt maxBytes=102400
fileCallId=abc step=READ_COMPLETED purpose=WRITE_VERIFICATION bytes=14 characters=14
fileCallId=abc step=COMPARE_SAVED_CONTENT expectedBytes=14 actualBytes=14 matches=true
fileCallId=abc step=WRITE_VERIFIED operation=createFile path=/.../test_agent/website/hello.txt bytes=14
fileCallId=abc step=RETURNED operation=createFile success=true durationMs=2 responseFields=[success, operation, path, bytesWritten, verified]
fileCallId=abc step=RESULT result={"success":true,"operation":"createFile","path":"/.../test_agent/website/hello.txt","bytesWritten":14,"verified":true}
```

`purpose=MODEL_REQUESTED_READ` means the model invoked `readFile`.
`purpose=UPDATE_VALIDATION` and `purpose=WRITE_VERIFICATION` are internal Java
reads within an existing invocation; neither is another model/tool round.
`SEARCH_LOCAL_FILE`, `READ_REQUESTED_LINES` and `EDIT_VALIDATION` distinguish
the local work for searching, line-range reads and targeted edits. For an edit,
`CHECK_FILE_VERSION` and `CHECK_REPLACEMENTS` show the checks before writing.
`step=FAILED` shows the exception type and mapped error reason. After a write,
`step=VERIFICATION_FAILED` means the file may have changed but verification did
not succeed. Both paths still finish with `RETURNED` and the exact `RESULT` JSON.

DEBUG payloads include complete supplied and returned file text, with newlines
escaped as JSON. Switch the property to `INFO` to hide those payloads while
keeping execution steps and result field names. These console logs do not enter
the model's context or add LLM tokens. They show tool execution, not the model's
private reasoning. Existing `AgentTraceService` logs still show model calls and
tool steps around these filesystem entries. Logging itself does not change the
tool result or response format.

## Build

```bash
./mvnw clean verify
```

Tests check remembered messages, isolation between conversations, weather tool calls
with memory, normal chat, prompt preservation, input validation and the annotated
weather, calculator and search tool round trips using a simulated LLM. Search tests
use a local HTTP server to check Serper's POST request, authentication header,
compact results, missing keys, validation and provider errors. Calculator tests cover
the four operations and error cases. Shopping tests check the POST request, India
settings, optional budgets, INR price parsing, exact budget boundaries, output limits,
missing data and safe API errors. The chat integration test checks shopping progress,
results sent back to the model and final usage; the native SDK test checks the optional
budget in the tool schema. Local HTTP tests also check coordinate
lookup, weather data and units, unknown cities and provider failures. Live chat
requests need your OpenRouter key and quota.
Stream tests additionally cover plain text delivery before the model finishes,
multiple tools, usage across model rounds, cross-endpoint memory, model/tool errors,
missing usage, cancellation, timeouts and concurrent request isolation. Local OpenRouter integration tests exercise the real OpenAI SDK against a fake
HTTP server. They verify the URL, authorization header, model selection, tool
loop, memory, usage/missing usage, API errors, retries and timeouts. They use no
real keys or API quota. A live OpenRouter check requires your configured key.
Filesystem tests use temporary directories to check actual creation, reads and
updates, overwrite prevention, missing files, absolute paths, traversal and symlinks,
UTF-8 and byte limits. Listing tests check names/types without contents, empty and
missing directories, files supplied as directories, symlinks, pagination, and
Desktop/home aliases using a disposable home directory. Read-back tests also simulate changed content and an unreadable
file after writing, checking that neither returns verified success. A native SDK
integration test runs the original four file tools through
the real Spring AI loop with simulated model replies and checks the final on-disk
contents, stream, verified results and usage totals. Another native SDK test checks
directory listing registration, the optional offset, streamed progress and the
metadata-only result sent back to the model. No real project or Desktop files are
modified by these tests.
Targeted-edit tests also check bounded/paginated search excerpts, long Unicode
lines, exact CRLF line reads, unchanged unrelated content, explicit replacement
counts, stale/missing versions, invalid edits and failed read-back verification.
Native SDK tests inspect outgoing model requests to verify that an access check
returns metadata only and a CSS edit sends snippets/replacement blocks rather
than the complete file. They use simulated provider replies and do not establish
how often a live model chooses each tool or measure real token savings.
Memory-projection tests check short/non-file replies, unformatted long file
replies, unchanged user/tool messages, metadata preservation and independent
storage copies. The native SDK regression test creates a file, makes the fake
provider repeat 200 lines of source in the final answer, and then sends another
user request. It checks the real memory store and outgoing wire messages: code
is still present in the current tool loop and visible answer, but absent from
the following user turn. It also verifies that no summarization call was added.
The JAR is generated at `target/weather-agent-0.0.1-SNAPSHOT.jar`.

See [the Qwen live evaluation](docs/qwen-evaluation.md) for eight real requests,
observed tool calls, timings, token totals and limitations.

References: [Spring AI tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html),
[Spring AI OpenAI configuration](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html).
[OpenRouter quickstart](https://openrouter.ai/docs/quickstart).
[Qwen3.7 Flash on OpenRouter](https://openrouter.ai/qwen/qwen3.7-flash).
[Spring AI chat memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html).
[Serper Google Search API](https://serper.dev/).
[Open-Meteo weather documentation](https://open-meteo.com/en/docs),
[Open-Meteo geocoding documentation](https://open-meteo.com/en/docs/geocoding-api).
