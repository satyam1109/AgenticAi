package com.example.weatheragent.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
public class FileSystemService {
    private static final Logger log = LoggerFactory.getLogger(FileSystemService.class);
    private static final int MAX_FILE_BYTES = 100 * 1024;
    private static final int DIRECTORY_PAGE_SIZE = 100;
    private static final int MAX_READ_LINES = 40;
    private static final int MAX_CONTEXT_CHARACTERS = 6000;
    private static final int MAX_SEARCH_MATCHES = 5;
    private static final int MAX_SEARCH_EXCERPT = 1000;
    private final Path basePath;
    private final Path homePath;

    public FileSystemService(@Value("${agent.files.base-path}") String basePath,
                             @Value("${user.home}") String homePath) throws IOException {
        // Canonicalize once so relative paths start from the configured project directory.
        this.basePath = Path.of(basePath).toRealPath();
        if (!Files.isDirectory(this.basePath)) {
            throw new IllegalArgumentException("agent.files.base-path must be an existing directory.");
        }
        // Java knows the account running this application; the model need not guess a username.
        this.homePath = Path.of(homePath).toRealPath();
        log.info("Filesystem tools ready: basePath={} homePath={} maxFileBytes={}", this.basePath, this.homePath, MAX_FILE_BYTES);
    }

    @Tool(description = "List immediate files and subdirectories without reading file contents or recursively browsing. Supports project-relative paths, Desktop/... and ~/... without a username, and explicit absolute paths. Returns up to 100 entries; if hasMore is true, call again with nextOffset.")
    public JsonNode listDirectory(
            @ToolParam(description = "Directory path, e.g. website, Desktop/test_agent_1 or ~/Desktop/test_agent_1") String path,
            @ToolParam(description = "Optional zero-based offset for the next page; omit for the first page", required = false) Integer offset) {
        long started = System.nanoTime();
        String callId = logCalled("listDirectory", JsonNodeFactory.instance.objectNode()
                .put("path", path).put("offset", offset));
        JsonNode result;
        try {
            int start = offset == null ? 0 : offset;
            if (start < 0) throw new IllegalArgumentException("Offset must be zero or greater.");
            Path target = resolvePath(callId, path);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(target.toString());
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("The path must be a directory, not a file or symbolic link.");
            }
            log.info("fileCallId={} step=LIST_DIRECTORY_START path={} offset={} pageSize={}",
                    callId, target, start, DIRECTORY_PAGE_SIZE);
            // Close the stream promptly. One extra entry tells us whether another page exists.
            try (var children = Files.list(target)) {
                var page = children.sorted().skip(start).limit(DIRECTORY_PAGE_SIZE + 1L).toList();
                var listing = success("listDirectory", target).put("offset", start);
                var entries = listing.putArray("entries");
                for (Path child : page.subList(0, Math.min(page.size(), DIRECTORY_PAGE_SIZE))) {
                    String type = Files.isSymbolicLink(child) ? "symbolic_link"
                            : Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ? "directory"
                            : Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) ? "file" : "other";
                    entries.addObject().put("name", child.getFileName().toString()).put("type", type);
                }
                boolean hasMore = page.size() > DIRECTORY_PAGE_SIZE;
                listing.put("count", entries.size()).put("hasMore", hasMore);
                if (hasMore) listing.put("nextOffset", start + entries.size());
                log.info("fileCallId={} step=LIST_DIRECTORY_COMPLETED count={} hasMore={}", callId, entries.size(), hasMore);
                result = listing;
            }
        } catch (UncheckedIOException e) {
            // Directory-stream errors can occur during iteration, after Files.list has returned.
            result = failure(callId, "listDirectory", path, e.getCause());
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "listDirectory", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Create a directory and any missing parents. Relative paths use the configured project directory; use an absolute path only for a location specified by the user. Existing directories are accepted.")
    public JsonNode createDirectory(@ToolParam(description = "Directory path, e.g. website or an explicitly requested absolute path") String path) {
        long started = System.nanoTime();
        String callId = logCalled("createDirectory", JsonNodeFactory.instance.objectNode().put("path", path));
        JsonNode result;
        try {
            Path target = resolvePath(callId, path);
            log.info("fileCallId={} step=CREATE_DIRECTORIES path={} existingDirectory={}",
                    callId, target, Files.isDirectory(target));
            Files.createDirectories(target);
            log.info("fileCallId={} step=CREATE_DIRECTORIES_COMPLETED path={}", callId, target);
            result = success("createDirectory", target);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "createDirectory", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Create a new UTF-8 text file (up to 100 KiB), creating missing parent directories. Reopens the saved file and verifies its content before returning success. The path includes the filename and extension. Fails if the file already exists; use updateFile to change it.")
    public synchronized JsonNode createFile(
            @ToolParam(description = "Full file path including filename and extension, e.g. website/index.html") String path,
            @ToolParam(description = "Complete text to write into the new file") String content) {
        long started = System.nanoTime();
        String callId = logCalled("createFile", JsonNodeFactory.instance.objectNode()
                .put("path", path).put("content", content));
        JsonNode result;
        try {
            byte[] bytes = textBytes(callId, content);
            Path target = resolvePath(callId, path);
            if (target.getParent() == null) throw new IllegalArgumentException("A file path must include a filename, not just the filesystem root.");
            log.info("fileCallId={} step=ENSURE_PARENT_DIRECTORY path={}", callId, target.getParent());
            Files.createDirectories(target.getParent());
            // CREATE_NEW checks existence atomically and never overwrites an existing file.
            log.info("fileCallId={} step=WRITE path={} mode=CREATE_NEW bytes={}", callId, target, bytes.length);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            log.info("fileCallId={} step=WRITE_COMPLETED path={}", callId, target);
            result = verifiedWriteResult(callId, "createFile", target, bytes);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "createFile", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Check a file or directory's type, size, modification time and readability without returning its contents. Use this for existence/access questions, not readFile.")
    public JsonNode getFileInfo(@ToolParam(description = "File or directory path to check") String path) {
        long started = System.nanoTime();
        String callId = logCalled("getFileInfo", JsonNodeFactory.instance.objectNode().put("path", path));
        JsonNode result;
        try {
            Path target = resolvePath(callId, path);
            var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String type = attributes.isSymbolicLink() ? "symbolic_link" : attributes.isDirectory() ? "directory"
                    : attributes.isRegularFile() ? "file" : "other";
            log.info("fileCallId={} step=READ_METADATA path={} type={} bytes={}", callId, target, type, attributes.size());
            result = success("getFileInfo", target).put("type", type).put("sizeBytes", attributes.size())
                    .put("readable", Files.isReadable(target)).put("lastModified", attributes.lastModifiedTime().toString());
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "getFileInfo", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Search an existing UTF-8 file for case-sensitive literal text, not a regex. Returns up to 5 matching lines with nearby context (up to 1000 characters per excerpt) and a file version for replaceText. If hasMore, continue with nextStartLine. Use for targeted edits instead of reading the entire file.")
    public JsonNode searchInFile(
            @ToolParam(description = "Existing text file path") String path,
            @ToolParam(description = "Single-line literal text to find, up to 200 characters, e.g. .btn-primary or Satyam Singh") String searchText,
            @ToolParam(description = "Optional one-based line to start searching; omit for line 1", required = false) Integer startLine) {
        long started = System.nanoTime();
        String callId = logCalled("searchInFile", JsonNodeFactory.instance.objectNode()
                .put("path", path).put("searchText", searchText).put("startLine", startLine));
        JsonNode result;
        try {
            if (searchText == null || searchText.isEmpty() || searchText.length() > 200
                    || searchText.contains("\n") || searchText.contains("\r")) {
                throw new IllegalArgumentException("Search text must be a non-empty single-line literal of up to 200 characters.");
            }
            int firstLine = startLine == null ? 1 : startLine;
            if (firstLine < 1) throw new IllegalArgumentException("Start line must be 1 or greater.");
            Path target = resolvePath(callId, path);
            String content = readText(callId, target, "SEARCH_LOCAL_FILE");
            List<String> lines = fileLines(content);
            var matchingLines = new ArrayList<Integer>();
            for (int index = firstLine - 1; index < lines.size(); index++) {
                if (lines.get(index).contains(searchText)) matchingLines.add(index);
            }
            var search = success("searchInFile", target).put("version", fileVersion(content)).put("totalLines", lines.size());
            var matches = search.putArray("matches");
            int count = Math.min(MAX_SEARCH_MATCHES, matchingLines.size());
            for (int index : matchingLines.subList(0, count)) {
                int from = Math.max(0, index - 2);
                int to = Math.min(lines.size(), index + 3);
                String context = String.join("", lines.subList(from, to));
                int matchPosition = String.join("", lines.subList(from, index)).length() + lines.get(index).indexOf(searchText);
                String excerpt = excerptAroundMatch(context, matchPosition);
                var match = matches.addObject().put("line", index + 1).put("context", excerpt)
                        .put("contextTruncated", excerpt.length() != context.length());
                // Only label a complete line range when its context has not been clipped.
                if (excerpt.length() == context.length()) match.put("startLine", from + 1).put("endLine", to);
            }
            boolean hasMore = matchingLines.size() > count;
            search.put("count", count).put("hasMore", hasMore);
            if (hasMore) search.put("nextStartLine", matchingLines.get(count - 1) + 2);
            log.info("fileCallId={} step=SEARCH_COMPLETED matchingLinesReturned={} hasMore={}", callId, count, hasMore);
            result = search;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "searchInFile", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Read a small inclusive one-based line range from an existing UTF-8 text file. Maximum 40 lines and 6000 characters per call. Returns exact text and a file version for replaceText. Use to expand context after searchInFile.")
    public JsonNode readFileLines(
            @ToolParam(description = "Existing text file path") String path,
            @ToolParam(description = "First line to return, starting at 1") int startLine,
            @ToolParam(description = "Last line to return, inclusive; at most 40 lines per call") int endLine) {
        long started = System.nanoTime();
        String callId = logCalled("readFileLines", JsonNodeFactory.instance.objectNode()
                .put("path", path).put("startLine", startLine).put("endLine", endLine));
        JsonNode result;
        try {
            if (startLine < 1 || endLine < startLine || (long) endLine - startLine + 1 > MAX_READ_LINES) {
                throw new IllegalArgumentException("Use an inclusive range of 1 to 40 lines, starting at line 1 or greater.");
            }
            Path target = resolvePath(callId, path);
            String content = readText(callId, target, "READ_REQUESTED_LINES");
            List<String> lines = fileLines(content);
            if (startLine > Math.max(1, lines.size())) throw new IllegalArgumentException("Start line is beyond the end of the file.");
            int lastLine = Math.min(endLine, lines.size());
            String selected = lines.isEmpty() ? "" : String.join("", lines.subList(startLine - 1, lastLine));
            if (selected.length() > MAX_CONTEXT_CHARACTERS) {
                throw new IllegalArgumentException("Requested range exceeds 6000 characters. Request fewer lines or use searchInFile.");
            }
            log.info("fileCallId={} step=LINE_RANGE_RETURNED startLine={} endLine={} characters={}",
                    callId, startLine, lastLine, selected.length());
            result = success("readFileLines", target).put("version", fileVersion(content))
                    .put("totalLines", lines.size()).put("startLine", startLine).put("endLine", lastLine).put("content", selected);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "readFileLines", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Read the COMPLETE existing UTF-8 text file up to 100 KiB. Use only when the user needs the whole file. For access checks use getFileInfo; for partial edits use searchInFile/readFileLines and replaceText.")
    public JsonNode readFile(@ToolParam(description = "Path of the text file to read, including filename and extension") String path) {
        long started = System.nanoTime();
        String callId = logCalled("readFile", JsonNodeFactory.instance.objectNode().put("path", path));
        JsonNode result;
        try {
            Path target = resolvePath(callId, path);
            String content = readText(callId, target, "MODEL_REQUESTED_READ");
            result = success("readFile", target).put("version", fileVersion(content)).put("content", content);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "readFile", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Replace the COMPLETE contents of an existing UTF-8 text file up to 100 KiB. Use only for a requested full rewrite, not a partial edit. For small changes use replaceText. Verifies saved contents and never creates a missing file.")
    public synchronized JsonNode updateFile(
            @ToolParam(description = "Path of the existing text file to update, including filename and extension") String path,
            @ToolParam(description = "Complete replacement text, preserving any existing content the user wants to keep") String content) {
        long started = System.nanoTime();
        String callId = logCalled("updateFile", JsonNodeFactory.instance.objectNode()
                .put("path", path).put("content", content));
        JsonNode result;
        try {
            byte[] bytes = textBytes(callId, content);
            Path target = resolvePath(callId, path);
            // This is Java's internal validation, not another model-requested readFile call.
            readText(callId, target, "UPDATE_VALIDATION");
            // No CREATE option: a missing file must not be silently created by an update.
            log.info("fileCallId={} step=WRITE path={} mode=REPLACE_EXISTING bytes={}", callId, target, bytes.length);
            Files.write(target, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            log.info("fileCallId={} step=WRITE_COMPLETED path={}", callId, target);
            result = verifiedWriteResult(callId, "updateFile", target, bytes);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "updateFile", path, e);
        }
        return logReturned(callId, started, result);
    }

    @Tool(description = "Edit an existing text file by replacing exact literal oldText with newText, preserving everything else. Both blocks are limited to 6000 characters. Requires the version returned by searchInFile/readFileLines/readFile. Fails without writing if the version or occurrence count differs. Verifies the saved file and returns metadata only; never creates a missing file.")
    public synchronized JsonNode replaceText(
            @ToolParam(description = "Existing text file path") String path,
            @ToolParam(description = "Exact text to replace, including whitespace and any necessary surrounding context; not a regex") String oldText,
            @ToolParam(description = "Replacement text only, not the complete file") String newText,
            @ToolParam(description = "Exact file version from the latest search or read result") String expectedVersion,
            @ToolParam(description = "Number of exact occurrences to replace; omit for 1. Supply the count explicitly for a requested replacement across multiple locations", required = false) Integer expectedOccurrences) {
        long started = System.nanoTime();
        String callId = logCalled("replaceText", JsonNodeFactory.instance.objectNode()
                .put("path", path).put("oldText", oldText).put("newText", newText)
                .put("expectedVersion", expectedVersion).put("expectedOccurrences", expectedOccurrences));
        JsonNode result;
        try {
            if (oldText == null || oldText.isEmpty() || newText == null
                    || oldText.length() > MAX_CONTEXT_CHARACTERS || newText.length() > MAX_CONTEXT_CHARACTERS) {
                throw new IllegalArgumentException("Supply non-empty oldText and non-null newText, each at most 6000 characters. Use smaller edits for larger changes.");
            }
            int expected = expectedOccurrences == null ? 1 : expectedOccurrences;
            if (expected < 1) throw new IllegalArgumentException("Expected occurrences must be 1 or greater.");
            Path target = resolvePath(callId, path);
            String original = readText(callId, target, "EDIT_VALIDATION");
            boolean versionMatches = fileVersion(original).equals(expectedVersion);
            log.info("fileCallId={} step=CHECK_FILE_VERSION matches={}", callId, versionMatches);
            if (!versionMatches) {
                throw new IllegalArgumentException("File version differs or is missing. Search or read the file again before editing; no write was performed.");
            }
            int count = 0;
            for (int index = 0; (index = original.indexOf(oldText, index)) >= 0; index += oldText.length()) count++;
            log.info("fileCallId={} step=CHECK_REPLACEMENTS expected={} actual={}", callId, expected, count);
            if (count != expected) {
                throw new IllegalArgumentException("Expected " + expected + " exact occurrences but found " + count
                        + ". Search again and use a unique surrounding block or the correct explicit count; no write was performed.");
            }
            // Java constructs the complete result locally; the model only supplied the small edit.
            String updated = original.replace(oldText, newText);
            byte[] bytes = textBytes(callId, updated);
            log.info("fileCallId={} step=WRITE path={} mode=TARGETED_REPLACEMENT replacements={} bytes={}", callId, target, count, bytes.length);
            Files.write(target, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            log.info("fileCallId={} step=WRITE_COMPLETED path={}", callId, target);
            var verified = (ObjectNode) verifiedWriteResult(callId, "replaceText", target, bytes);
            if (verified.path("success").asBoolean()) verified.put("replacements", count).put("version", fileVersion(updated));
            result = verified;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            result = failure(callId, "replaceText", path, e);
        }
        return logReturned(callId, started, result);
    }

    // Keep line endings so a model can copy a multi-line block exactly, including CRLF.
    private List<String> fileLines(String content) {
        if (content.isEmpty()) return List.of();
        var lines = Arrays.asList(content.split("(?<=\\n)", -1));
        return lines.getLast().isEmpty() ? lines.subList(0, lines.size() - 1) : lines;
    }

    private String excerptAroundMatch(String context, int matchPosition) {
        if (context.length() <= MAX_SEARCH_EXCERPT) return context;
        int start = Math.max(0, matchPosition - MAX_SEARCH_EXCERPT / 2);
        int end = Math.min(context.length(), start + MAX_SEARCH_EXCERPT);
        // Avoid splitting a UTF-16 surrogate pair when clipping a long/minified line.
        if (start > 0 && Character.isLowSurrogate(context.charAt(start))) start++;
        if (end < context.length() && Character.isHighSurrogate(context.charAt(end - 1))) end--;
        return context.substring(start, end);
    }

    private String fileVersion(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for file version checks.", e);
        }
    }

    private Path resolvePath(String callId, String path) throws IOException {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("A non-blank path is required.");
        Path requested = Path.of(path);
        String mode = requested.isAbsolute() ? "ABSOLUTE" : "RELATIVE";
        // These aliases mean locations in the app account's home, not folders in the project.
        if (path.equals("~") || path.startsWith("~/")) {
            requested = path.equals("~") ? homePath : homePath.resolve(path.substring(2));
            mode = "HOME";
        } else if (!requested.isAbsolute() && requested.getName(0).toString().equals("Desktop")) {
            requested = homePath.resolve(requested);
            mode = "DESKTOP";
        }
        Path target = (requested.isAbsolute() ? requested : basePath.resolve(requested)).normalize();
        log.info("fileCallId={} step=RESOLVE_PATH mode={} basePath={} resolvedPath={}",
                callId, mode, basePath, target);
        if (!requested.isAbsolute()) {
            if (!target.startsWith(basePath)) {
                throw new IllegalArgumentException("Relative paths must stay inside the project. Use an explicit absolute path for another location.");
            }
            // Also check the nearest existing ancestor: a symbolic link must not bypass that rule.
            Path existing = target;
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            log.debug("fileCallId={} step=CHECK_RELATIVE_PATH nearestExistingAncestor={}", callId, existing);
            if (!existing.toRealPath().startsWith(basePath)) {
                throw new IllegalArgumentException("This relative path follows a symbolic link outside the project. Use an explicit absolute path instead.");
            }
        }
        log.info("fileCallId={} step=PATH_ACCEPTED path={}", callId, target);
        return target;
    }

    private byte[] textBytes(String callId, String content) {
        log.info("fileCallId={} step=VALIDATE_WRITE_CONTENT characters={} maxBytes={}",
                callId, content == null ? null : content.length(), MAX_FILE_BYTES);
        if (content == null) throw new IllegalArgumentException("File content is required; an empty string is allowed.");
        if (content.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("Content exceeds the 100 KiB text limit.");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Content exceeds the 100 KiB text limit.");
        if (content.indexOf('\0') >= 0) throw new IllegalArgumentException("Only text files are supported, not binary content.");
        log.info("fileCallId={} step=WRITE_CONTENT_ACCEPTED utf8Bytes={}", callId, bytes.length);
        return bytes;
    }

    private String readText(String callId, Path target, String purpose) throws IOException {
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        log.info("fileCallId={} step=READ_CHECK purpose={} path={} exists={}", callId, purpose, target, exists);
        if (!exists) throw new NoSuchFileException(target.toString());
        // Avoid reading directories, symbolic-link files or special files such as named pipes.
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The path must be a regular text file, not a directory, symbolic link or special file.");
        }
        // Bound the read itself, so a large file is never loaded into the model's context.
        log.info("fileCallId={} step=READ_START purpose={} path={} maxBytes={}", callId, purpose, target, MAX_FILE_BYTES);
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds the 100 KiB text limit.");
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (content.indexOf('\0') >= 0) throw new IllegalArgumentException("Only text files are supported, not binary content.");
            log.info("fileCallId={} step=READ_COMPLETED purpose={} bytes={} characters={}",
                    callId, purpose, bytes.length, content.length());
            return content;
        }
    }

    private JsonNode verifiedWriteResult(String callId, String operation, Path target, byte[] expected) {
        try {
            log.info("fileCallId={} step=VERIFY_WRITE_START path={} expectedBytes={}", callId, target, expected.length);
            // readText reopens the file with the same UTF-8, size and file-type checks as readFile.
            byte[] actual = readText(callId, target, "WRITE_VERIFICATION").getBytes(StandardCharsets.UTF_8);
            boolean matches = Arrays.equals(expected, actual);
            log.info("fileCallId={} step=COMPARE_SAVED_CONTENT expectedBytes={} actualBytes={} matches={}",
                    callId, expected.length, actual.length, matches);
            if (!matches) throw new IOException("Saved content does not match the write.");
            log.info("fileCallId={} step=WRITE_VERIFIED operation={} path={} bytes={}", callId, operation, target, actual.length);
            return success(operation, target).put("bytesWritten", expected.length).put("verified", true);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            // The write already completed. Do not claim nothing changed or automatically retry it.
            log.warn("fileCallId={} step=VERIFICATION_FAILED operation={} path={} type={}",
                    callId, operation, target, e.getClass().getSimpleName());
            return JsonNodeFactory.instance.objectNode().put("success", false)
                    .put("operation", operation).put("path", target.toString())
                    .put("verified", false).put("writeCompleted", true)
                    .put("errorCode", "VERIFICATION_FAILED")
                    .put("error", "The write completed, but read-back verification failed. The file may already have changed; inspect it before retrying.");
        }
    }

    private ObjectNode success(String operation, Path path) {
        return JsonNodeFactory.instance.objectNode().put("success", true)
                .put("operation", operation).put("path", path.toString());
    }

    private JsonNode failure(String callId, String operation, String path, Exception error) {
        String message;
        if (error instanceof FileAlreadyExistsException) message = "The path already exists. Use updateFile to change an existing file.";
        else if (error instanceof NoSuchFileException) message = "The requested path does not exist.";
        else if (error instanceof AccessDeniedException || error instanceof SecurityException) message = "Permission denied for this location.";
        else if (error instanceof CharacterCodingException) message = "The file must contain valid UTF-8 text.";
        else if (error instanceof IllegalArgumentException) message = error.getMessage();
        else message = "Filesystem operation failed (" + error.getClass().getSimpleName() + ").";
        log.warn("fileCallId={} step=FAILED operation={} type={} reason={}",
                callId, operation, error.getClass().getSimpleName(), message);
        return JsonNodeFactory.instance.objectNode().put("success", false)
                .put("operation", operation).put("path", path).put("error", message);
    }

    // One ID connects all log lines for this particular Java tool invocation.
    private String logCalled(String operation, ObjectNode arguments) {
        String callId = UUID.randomUUID().toString();
        log.info("fileCallId={} step=CALLED operation={} path={}", callId, operation, arguments.get("path"));
        // JSON escapes newlines so even HTML/CSS arguments stay inside one labelled log entry.
        log.debug("fileCallId={} step=ARGUMENTS arguments={}", callId, arguments);
        return callId;
    }

    private JsonNode logReturned(String callId, long started, JsonNode result) {
        log.info("fileCallId={} step=RETURNED operation={} success={} durationMs={} responseFields={}",
                callId, result.path("operation").asString(), result.path("success").asBoolean(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), result.propertyNames());
        // This is the final JSON returned to Spring AI, including content for readFile.
        log.debug("fileCallId={} step=RESULT result={}", callId, result);
        return result;
    }
}
