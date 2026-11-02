package com.example.weatheragent.service;

import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/** Changes only what is saved for the next user turn, never the live model/tool exchange. */
public final class FileAnswerMemory implements ChatMemory {
    private static final Logger log = LoggerFactory.getLogger(FileAnswerMemory.class);
    private static final int MAX_FILE_REPLY_CHARACTERS = 2000;
    private static final int MAX_FILE_REPLY_LINES = 40;
    private final ChatMemory delegate;
    private final Function<String, String> fileResultsForMemory;

    // Created explicitly in LlmService, not as a ChatMemory bean: Boot still supplies the real store.
    public FileAnswerMemory(ChatMemory delegate, Function<String, String> fileResultsForMemory) {
        this.delegate = delegate;
        this.fileResultsForMemory = fileResultsForMemory;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        delegate.add(conversationId, messages.stream().map(message -> forMemory(conversationId, message)).toList());
    }

    private Message forMemory(String conversationId, Message message) {
        if (!(message instanceof AssistantMessage assistant) || assistant.hasToolCalls()) return message;
        String answer = assistant.getText();
        if (answer == null || (answer.length() <= MAX_FILE_REPLY_CHARACTERS
                && answer.lines().limit(MAX_FILE_REPLY_LINES + 1L).count() <= MAX_FILE_REPLY_LINES)) return message;

        String fileResults = fileResultsForMemory.apply(conversationId);
        // Scope this rule to an actual filesystem task. Long ordinary chat replies are unchanged.
        if (fileResults == null) return message;
        log.info("conversationId={} memory=COMPACT_FILE_REPLY originalCharacters={} storedCharacters={}",
                conversationId, answer.length(), fileResults.length());
        // Copy the message for storage. Do not mutate the original response seen by the user.
        return assistant.mutate().content(fileResults).build();
    }

    @Override
    public List<Message> get(String conversationId) {
        return delegate.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }
}
