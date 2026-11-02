package com.example.weatheragent;

import com.example.weatheragent.service.FileAnswerMemory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

class FileAnswerMemoryTests {
    @Test
    void storesASeparateCompactCopyWithoutChangingTheVisibleAnswerOrMetadata() {
        var store = MessageWindowChatMemory.builder().build();
        String receipt = "Created /project/index.html; verified: true.";
        var memory = new FileAnswerMemory(store, conversation -> receipt);
        String code = "<p>GENERATED_FILE_BODY</p>\n".repeat(200);
        var original = AssistantMessage.builder().content("Created index.html:\n```html\n" + code + "```\n")
                .properties(Map.of("source", "test")).build();
        memory.add("chat", List.of(new UserMessage("Create index.html"), original));

        var saved = (AssistantMessage) store.get("chat").get(1);
        assertThat(saved.getText()).isEqualTo(receipt).doesNotContain("GENERATED_FILE_BODY");
        assertThat(saved.getMetadata()).containsEntry("source", "test");
        assertThat(saved).isNotSameAs(original);
        assertThat(original.getText()).contains(code);
    }

    @Test
    void handlesUnfencedCodeAndManyShortLinesWithoutGuessingTheProgrammingLanguage() {
        var store = MessageWindowChatMemory.builder().build();
        var memory = new FileAnswerMemory(store, conversation -> "Recorded file result.");
        for (String answer : List.of("x\n".repeat(100), "x".repeat(2001))) {
            memory.add("chat", List.of(new UserMessage("Create a file"), new AssistantMessage(answer)));
            assertThat(store.get("chat").getLast().getText()).isEqualTo("Recorded file result.");
        }
    }

    @Test
    void preservesShortFinalAnswersExactlyAndDelegatesClearAndConversationIsolation() {
        var store = MessageWindowChatMemory.builder().build();
        var memory = new FileAnswerMemory(store, conversation -> "Should not replace a short answer.");
        var answer = new AssistantMessage("Created both files in /project/website.");
        memory.add("first", List.of(new UserMessage("Create files"), answer));
        memory.add("second", new UserMessage("Another conversation"));

        assertThat(memory.get("first").getLast()).isSameAs(answer);
        memory.clear("first");
        assertThat(store.get("first")).isEmpty();
        assertThat(store.get("second")).hasSize(1);
    }

    @Test
    void doesNotChangeLongNonFileAnswersUserMessagesOrToolExchanges() {
        var store = MessageWindowChatMemory.builder().build();
        String longText = "A long ordinary explanation.\n".repeat(100);
        var ordinaryMemory = new FileAnswerMemory(store, conversation -> null);
        ordinaryMemory.add("ordinary", List.of(new UserMessage("Explain"), new AssistantMessage(longText)));
        assertThat(store.get("ordinary").getLast().getText()).isEqualTo(longText);

        var guarded = new FileAnswerMemory(store, conversation -> {
            throw new AssertionError("User/tool messages must not be projected");
        });
        var toolCall = AssistantMessage.builder().content(longText)
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "createFile", longText))).build();
        var toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("id", "createFile", "{\"success\":true}"))).build();
        guarded.add("tools", List.of(new UserMessage(longText), toolCall, toolResult));
        assertThat(store.get("tools")).containsExactly(new UserMessage(longText), toolCall, toolResult);
    }
}
