package com.example.weatheragent;

import com.example.weatheragent.service.FileSystemService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

// All operations use disposable directories, never the user's project or Desktop files.
class FileSystemServiceTests {
    @TempDir Path temporary;
    private Path workspace;
    private Path home;
    private FileSystemService service;

    @BeforeEach
    void prepareWorkspace() throws Exception {
        workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath();
        home = Files.createDirectory(temporary.resolve("home")).toRealPath();
        service = new FileSystemService(workspace.toString(), home.toString());
    }

    @Test
    void metadataChecksDoNotReadOrReturnFileContents() throws Exception {
        Path target = Files.write(workspace.resolve("large.bin"), new byte[105000]);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            var info = service.getFileInfo("large.bin");
            assertThat(info.path("success").asBoolean()).isTrue();
            assertThat(info.path("type").asString()).isEqualTo("file");
            assertThat(info.path("sizeBytes").asLong()).isEqualTo(105000);
            assertThat(info.has("content")).isFalse();
            files.verify(() -> Files.newInputStream(any(Path.class), any(LinkOption.class)), never());
        }
        assertThat(target).exists();
        assertThat(service.getFileInfo("missing.txt").path("error").asString()).contains("does not exist");
        assertThat(service.getFileInfo(".").path("type").asString()).isEqualTo("directory");
    }

    @Test
    void searchReturnsOnlyNearbyContextAndAPaginatableSetOfMatchingLines() throws Exception {
        StringBuilder content = new StringBuilder("unrelated header\n".repeat(100));
        for (int index = 0; index < 12; index++) content.append("item ").append(index).append(" NEEDLE\n").append("padding\n".repeat(10));
        content.append("unrelated footer\n".repeat(100));
        Files.writeString(workspace.resolve("large.txt"), content);

        var first = service.searchInFile("large.txt", "NEEDLE", null);
        var second = service.searchInFile("large.txt", "NEEDLE", first.path("nextStartLine").asInt());
        var third = service.searchInFile("large.txt", "NEEDLE", second.path("nextStartLine").asInt());

        assertThat(first.path("success").asBoolean()).isTrue();
        assertThat(first.path("version").asString()).matches("[0-9a-f]{64}");
        assertThat(first.path("count").asInt()).isEqualTo(5);
        assertThat(first.path("matches").get(0).path("line").asInt()).isEqualTo(101);
        assertThat(second.path("matches").get(0).path("line").asInt()).isEqualTo(156);
        assertThat(third.path("count").asInt()).isEqualTo(2);
        assertThat(third.path("hasMore").asBoolean()).isFalse();
        assertThat(first.toString()).doesNotContain(content.toString(), "unrelated footer");
        assertThat(service.searchInFile("large.txt", "needle", null).path("count").asInt()).isZero();
        assertThat(service.searchInFile("large.txt", "NEEDLE", 0).has("error")).isTrue();
        assertThat(service.searchInFile("large.txt", "", null).has("error")).isTrue();
    }

    @Test
    void longMinifiedLinesStillReturnABoundedExcerptAroundTheMatch() throws Exception {
        Files.writeString(workspace.resolve("minified.txt"), "🌍".repeat(4000) + "NEEDLE" + "x".repeat(4000));
        var result = service.searchInFile("minified.txt", "NEEDLE", null);
        var match = result.path("matches").get(0);
        String excerpt = match.path("context").asString();
        assertThat(excerpt).contains("NEEDLE").hasSizeLessThanOrEqualTo(1000);
        assertThat(new String(excerpt.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(excerpt);
        assertThat(match.path("contextTruncated").asBoolean()).isTrue();
        assertThat(match.has("startLine")).isFalse();
    }

    @Test
    void lineReadsPreserveExactCrLfAndRejectOversizedRanges() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "first\r\nsecond\r\nthird\r\n");
        var result = service.readFileLines("notes.txt", 2, 2);
        assertThat(result.path("content").asString()).isEqualTo("second\r\n");
        assertThat(result.path("totalLines").asInt()).isEqualTo(3);
        assertThat(result.path("version").asString())
                .isEqualTo(service.searchInFile("notes.txt", "second", null).path("version").asString());
        assertThat(service.readFileLines("notes.txt", 2, 10).path("content").asString()).isEqualTo("second\r\nthird\r\n");
        assertThat(service.readFileLines("notes.txt", 1, 41).has("error")).isTrue();
        assertThat(service.readFileLines("notes.txt", 0, 2).has("error")).isTrue();
        assertThat(service.readFileLines("notes.txt", 3, 2).has("error")).isTrue();
        assertThat(service.readFileLines("notes.txt", 4, 4).has("error")).isTrue();
        Files.writeString(workspace.resolve("long.txt"), "x".repeat(6001));
        assertThat(service.readFileLines("long.txt", 1, 1).path("error").asString()).contains("6000");
        Files.writeString(workspace.resolve("empty.txt"), "");
        assertThat(service.readFileLines("empty.txt", 1, 1).path("content").asString()).isEmpty();
    }

    @Test
    void targetedEditsPreserveAllUnrelatedTextAndVerifyTheWholeSavedFile() throws Exception {
        String original = "/* keep this */\r\n".repeat(100)
                + ".btn-primary {\r\n    color: blue;\r\n}\r\n"
                + ".other { color: blue; }\r\n";
        Path target = Files.writeString(workspace.resolve("style.css"), original);
        var search = service.searchInFile("style.css", ".btn-primary", null);
        String oldText = ".btn-primary {\r\n    color: blue;\r\n}";
        String newText = ".btn-primary {\r\n    color: red;\r\n}";

        var result = service.replaceText("style.css", oldText, newText, search.path("version").asString(), null);

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("verified").asBoolean()).isTrue();
        assertThat(result.path("replacements").asInt()).isEqualTo(1);
        assertThat(result.has("content")).isFalse();
        assertThat(result.path("version").asString()).isNotEqualTo(search.path("version").asString());
        assertThat(Files.readString(target)).isEqualTo(original.replace(oldText, newText));
    }

    @Test
    void ambiguousOrMissingTextRequiresTheCorrectExplicitOccurrenceCount() throws Exception {
        Path target = Files.writeString(workspace.resolve("notes.txt"), "Satyam Singh\nSatyam Singh\n");
        String version = service.searchInFile("notes.txt", "Satyam Singh", null).path("version").asString();
        assertThat(service.replaceText("notes.txt", "Satyam Singh", "Sachin Kalyan", version, null).path("error").asString())
                .contains("found 2", "no write");
        assertThat(service.replaceText("notes.txt", "not present", "hello", version, null).path("error").asString()).contains("found 0");
        assertThat(Files.readString(target)).isEqualTo("Satyam Singh\nSatyam Singh\n");
        var result = service.replaceText("notes.txt", "Satyam Singh", "Sachin Kalyan", version, 2);
        assertThat(result.path("replacements").asInt()).isEqualTo(2);
        assertThat(result.path("verified").asBoolean()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("Sachin Kalyan\nSachin Kalyan\n");
    }

    @Test
    void staleVersionsAndMissingVersionsCannotOverwriteNewerContents() throws Exception {
        Path target = Files.writeString(workspace.resolve("notes.txt"), "first value");
        String version = service.readFileLines("notes.txt", 1, 1).path("version").asString();
        Files.writeString(target, "first value plus an external change");
        assertThat(service.replaceText("notes.txt", "first", "second", version, null).path("error").asString()).contains("version differs");
        assertThat(service.replaceText("notes.txt", "first", "second", null, null).path("error").asString()).contains("version differs");
        assertThat(Files.readString(target)).isEqualTo("first value plus an external change");
        assertThat(service.replaceText("missing.txt", "first", "second", version, null).path("error").asString()).contains("does not exist");
        assertThat(workspace.resolve("missing.txt")).doesNotExist();
    }

    @Test
    void invalidOrOversizedEditsAndSymlinksFailWithoutChangingTheFile() throws Exception {
        Path target = Files.writeString(workspace.resolve("notes.txt"), "keep this");
        String version = service.readFileLines("notes.txt", 1, 1).path("version").asString();
        assertThat(service.replaceText("notes.txt", "", "hello", version, null).has("error")).isTrue();
        assertThat(service.replaceText("notes.txt", "keep", null, version, null).has("error")).isTrue();
        assertThat(service.replaceText("notes.txt", "keep", "x".repeat(6001), version, null).has("error")).isTrue();
        assertThat(service.replaceText("notes.txt", "keep", "hello", version, 0).has("error")).isTrue();
        assertThat(service.replaceText("notes.txt", "keep", "hello\0", version, null).has("error")).isTrue();
        Files.createSymbolicLink(workspace.resolve("link"), target);
        assertThat(service.replaceText("link", "keep", "hello", version, null).has("error")).isTrue();
        assertThat(service.searchInFile("../notes.txt", "keep", null).has("error")).isTrue();
        assertThat(Files.readString(target)).isEqualTo("keep this");
    }

    @Test
    void targetedEditDoesNotReportSuccessWhenReadBackVerificationFails() throws Exception {
        Path target = Files.writeString(workspace.resolve("notes.txt"), "old value");
        String version = service.readFileLines("notes.txt", 1, 1).path("version").asString();
        var reads = new AtomicInteger();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.newInputStream(any(Path.class), eq(LinkOption.NOFOLLOW_LINKS)))
                    .thenAnswer(invocation -> {
                        if (reads.incrementAndGet() == 2) throw new AccessDeniedException(target.toString());
                        return invocation.callRealMethod();
                    });
            var result = service.replaceText("notes.txt", "old", "new", version, null);
            assertThat(result.path("success").asBoolean()).isFalse();
            assertThat(result.path("writeCompleted").asBoolean()).isTrue();
            assertThat(result.path("errorCode").asString()).isEqualTo("VERIFICATION_FAILED");
            assertThat(result.has("replacements")).isFalse();
        }
        assertThat(Files.readString(target)).isEqualTo("new value");
    }

    @Test
    void listsImmediateEntriesWithoutReadingContentsOrFollowingLinks() throws Exception {
        Path directory = Files.createDirectory(workspace.resolve("website"));
        Files.createDirectory(directory.resolve("assets"));
        Files.writeString(directory.resolve("assets/nested.txt"), "nested file body");
        Files.writeString(directory.resolve("index.html"), "page body must not appear in listing");
        Files.write(directory.resolve("image.bin"), new byte[]{65, 0, 66});
        Files.writeString(directory.resolve(".hidden"), "hidden body");
        Files.createSymbolicLink(directory.resolve("link"), directory.resolve("assets"));

        var result = service.listDirectory("website", null);

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("path").asString()).isEqualTo(directory.toString());
        assertThat(result.path("count").asInt()).isEqualTo(5);
        assertThat(result.path("hasMore").asBoolean()).isFalse();
        assertThat(result.path("entries")).extracting(entry -> entry.path("name").asString())
                .containsExactly(".hidden", "assets", "image.bin", "index.html", "link");
        assertThat(result.path("entries")).extracting(entry -> entry.path("type").asString())
                .containsExactly("file", "directory", "file", "file", "symbolic_link");
        assertThat(result.toString()).doesNotContain("nested.txt", "file body", "page body", "hidden body", "\"content\"");
    }

    @Test
    void listsEmptyDirectoriesAndRejectsMissingFilesAndInvalidPaths() throws Exception {
        Files.createDirectory(workspace.resolve("empty"));
        Files.writeString(workspace.resolve("notes.txt"), "hello");
        Files.createSymbolicLink(workspace.resolve("link"), workspace.resolve("empty"));

        var empty = service.listDirectory("empty", null);
        assertThat(empty.path("success").asBoolean()).isTrue();
        assertThat(empty.path("entries")).isEmpty();
        assertThat(empty.path("hasMore").asBoolean()).isFalse();
        assertThat(service.listDirectory("missing", null).path("error").asString()).contains("does not exist");
        assertThat(workspace.resolve("missing")).doesNotExist();
        assertThat(service.listDirectory("notes.txt", null).path("error").asString()).contains("must be a directory");
        assertThat(service.listDirectory("link", null).path("error").asString()).contains("symbolic link");
        assertThat(service.listDirectory("../outside", null).path("error").asString()).contains("Relative paths");
        assertThat(service.listDirectory("empty", -1).path("error").asString()).contains("Offset");
        assertThat(service.listDirectory(null, null).has("error")).isTrue();
        assertThat(service.listDirectory(" ", null).has("error")).isTrue();
        assertThat(service.listDirectory("bad\0path", null).has("error")).isTrue();
    }

    @Test
    void paginatesLargeDirectoriesWithoutOmittingOrDuplicatingEntries() throws Exception {
        Path directory = Files.createDirectory(workspace.resolve("many"));
        for (int number = 0; number < 201; number++) {
            Files.writeString(directory.resolve("file-%03d.txt".formatted(number)), "body");
        }
        var first = service.listDirectory("many", null);
        var second = service.listDirectory("many", first.path("nextOffset").asInt());
        var third = service.listDirectory("many", second.path("nextOffset").asInt());

        assertThat(first.path("count").asInt()).isEqualTo(100);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("entries").get(0).path("name").asString()).isEqualTo("file-000.txt");
        assertThat(second.path("count").asInt()).isEqualTo(100);
        assertThat(second.path("entries").get(0).path("name").asString()).isEqualTo("file-100.txt");
        assertThat(third.path("count").asInt()).isEqualTo(1);
        assertThat(third.path("entries").get(0).path("name").asString()).isEqualTo("file-200.txt");
        assertThat(third.path("hasMore").asBoolean()).isFalse();
        assertThat(third.has("nextOffset")).isFalse();
        assertThat(service.listDirectory("many", 999).path("entries")).isEmpty();
    }

    @Test
    void resolvesDesktopAndHomePathsWithoutAUsernameForEveryFilesystemOperation() throws Exception {
        Path desktop = Files.createDirectory(home.resolve("Desktop"));
        Path folder = desktop.resolve("test_agent_1/mark1");
        assertThat(service.createDirectory("Desktop/test_agent_1/mark1").path("success").asBoolean()).isTrue();
        assertThat(service.createFile("~/Desktop/test_agent_1/mark1/index.html", "hello").path("success").asBoolean()).isTrue();
        assertThat(service.readFile("Desktop/test_agent_1/mark1/index.html").path("content").asString()).isEqualTo("hello");
        assertThat(service.updateFile("~/Desktop/test_agent_1/mark1/index.html", "welcome").path("verified").asBoolean()).isTrue();

        var result = service.listDirectory("Desktop/test_agent_1", null);
        assertThat(result.path("path").asString()).isEqualTo(desktop.resolve("test_agent_1").toString());
        assertThat(result.path("entries").get(0).path("name").asString()).isEqualTo("mark1");
        assertThat(service.listDirectory("~/Desktop/test_agent_1/mark1", null).path("entries").get(0).path("name").asString())
                .isEqualTo("index.html");
        assertThat(service.listDirectory("~", null).path("path").asString()).isEqualTo(home.toString());
        assertThat(service.listDirectory("Desktop", null).path("path").asString()).isEqualTo(desktop.toString());
        assertThat(service.listDirectory(folder.toString(), null).path("success").asBoolean()).isTrue();
        assertThat(Files.readString(folder.resolve("index.html"))).isEqualTo("welcome");
        assertThat(workspace.resolve("Desktop")).doesNotExist();
    }

    @Test
    void createsNestedDirectoriesAndAcceptsAnExistingDirectory() {
        var first = service.createDirectory("website/assets");
        assertThat(first.path("success").asBoolean()).isTrue();
        assertThat(first.path("operation").asString()).isEqualTo("createDirectory");
        assertThat(first.path("path").asString()).isEqualTo(workspace.resolve("website/assets").toString());
        assertThat(workspace.resolve("website/assets")).isDirectory();
        assertThat(service.createDirectory("website/assets").path("success").asBoolean()).isTrue();
    }

    @Test
    void createsTextFilesWithTheirNamesExtensionsAndMissingParents() throws Exception {
        for (String extension : new String[]{"html", "js", "txt", "css", "json", "java"}) {
            String path = "website/content." + extension;
            String content = "Hello मुंबई 🌍\n";
            var result = service.createFile(path, content);
            assertThat(result.path("success").asBoolean()).isTrue();
            assertThat(result.path("verified").asBoolean()).isTrue();
            assertThat(result.path("bytesWritten").asInt()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
            assertThat(Files.readString(workspace.resolve(path))).isEqualTo(content);
            assertThat(service.readFile(path).path("content").asString()).isEqualTo(content);
        }
    }

    @Test
    void acceptsAnExplicitAbsolutePathOutsideTheDefaultWorkspace() throws Exception {
        Path target = temporary.resolve("another-location/notes.txt").toAbsolutePath();
        assertThat(service.createDirectory(target.getParent().toString()).path("success").asBoolean()).isTrue();
        assertThat(service.createFile(target.toString(), "original").path("success").asBoolean()).isTrue();
        assertThat(service.readFile(target.toString()).path("content").asString()).isEqualTo("original");
        assertThat(service.updateFile(target.toString(), "changed").path("success").asBoolean()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("changed");
    }

    @Test
    void creatingAnExistingFileNeverOverwritesItsContents() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "keep this");
        var result = service.createFile("notes.txt", "replacement");
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("error").asString()).contains("already exists", "updateFile");
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("keep this");
    }

    @Test
    void updateReplacesTheCompleteContentsAndAllowsAnEmptyTextFile() throws Exception {
        Files.writeString(workspace.resolve("notes.txt"), "a much longer original value");
        var result = service.updateFile("notes.txt", "short");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("verified").asBoolean()).isTrue();
        assertThat(result.path("bytesWritten").asInt()).isEqualTo(5);
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("short");
        assertThat(service.updateFile("notes.txt", "").path("success").asBoolean()).isTrue();
        assertThat(service.readFile("notes.txt").path("content").asString()).isEmpty();
        assertThat(service.createFile("empty.txt", "").path("success").asBoolean()).isTrue();
    }

    @Test
    void readingOrUpdatingAMissingFileDoesNotCreateIt() {
        assertThat(service.readFile("missing/notes.txt").path("error").asString()).contains("does not exist");
        assertThat(service.updateFile("missing/notes.txt", "hello").path("error").asString()).contains("does not exist");
        assertThat(workspace.resolve("missing")).doesNotExist();
    }

    @Test
    void relativeTraversalCannotEscapeTheWorkspaceForAnyOperation() throws Exception {
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "private text");
        assertThat(service.readFile("../outside.txt").path("error").asString()).contains("Relative paths");
        assertThat(service.updateFile("../outside.txt", "changed").has("error")).isTrue();
        assertThat(service.createFile("../new.txt", "hello").has("error")).isTrue();
        assertThat(service.createDirectory("../new-directory").has("error")).isTrue();
        assertThat(Files.readString(outside)).isEqualTo("private text");
        assertThat(temporary.resolve("new.txt")).doesNotExist();
        assertThat(temporary.resolve("new-directory")).doesNotExist();
    }

    @Test
    void aSymbolicLinkCannotBypassRelativePathContainment() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("notes.txt"), "keep this");
        Files.createSymbolicLink(workspace.resolve("link"), outside);

        assertThat(service.readFile("link/notes.txt").path("error").asString()).contains("symbolic link outside");
        assertThat(service.updateFile("link/notes.txt", "replacement").has("error")).isTrue();
        assertThat(service.createFile("link/new.txt", "hello").has("error")).isTrue();
        assertThat(service.createDirectory("link/new-directory").has("error")).isTrue();
        assertThat(Files.readString(outside.resolve("notes.txt"))).isEqualTo("keep this");
        assertThat(outside.resolve("new.txt")).doesNotExist();
        assertThat(outside.resolve("new-directory")).doesNotExist();
    }

    @Test
    void aSymlinkedFileCannotBeReadOrOverwrittenEvenWithinTheWorkspace() throws Exception {
        Files.writeString(workspace.resolve("original.txt"), "keep this");
        Files.createSymbolicLink(workspace.resolve("alias.txt"), workspace.resolve("original.txt"));
        assertThat(service.readFile("alias.txt").has("error")).isTrue();
        assertThat(service.updateFile("alias.txt", "replacement").has("error")).isTrue();
        assertThat(service.createFile("alias.txt", "replacement").has("error")).isTrue();
        assertThat(Files.readString(workspace.resolve("original.txt"))).isEqualTo("keep this");
    }

    @Test
    void directoriesAreNotTreatedAsTextFiles() throws Exception {
        Files.createDirectory(workspace.resolve("folder"));
        assertThat(service.readFile("folder").path("error").asString()).contains("regular text file");
        assertThat(service.updateFile("folder", "hello").has("error")).isTrue();
        assertThat(service.createFile("folder", "hello").has("error")).isTrue();
        Files.writeString(workspace.resolve("notes.txt"), "keep this");
        assertThat(service.createDirectory("notes.txt").has("error")).isTrue();
        assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo("keep this");
    }

    @Test
    void invalidPathsAndContentReturnErrorsBeforeCreatingParents() {
        for (String path : new String[]{null, " ", "bad\0path"}) {
            assertThat(service.createDirectory(path).has("error")).isTrue();
            assertThat(service.createFile(path, "hello").has("error")).isTrue();
            assertThat(service.readFile(path).has("error")).isTrue();
            assertThat(service.updateFile(path, "hello").has("error")).isTrue();
        }
        assertThat(service.createFile("new/null.txt", null).has("error")).isTrue();
        assertThat(service.createFile("new/binary.txt", "hello\0world").has("error")).isTrue();
        assertThat(workspace.resolve("new")).doesNotExist();
    }

    @Test
    void theFilesystemRootIsNotAValidFileName() {
        assertThat(service.createFile(workspace.getRoot().toString(), "hello").path("error").asString())
                .contains("must include a filename");
    }

    @Test
    void invalidUtf8AndBinaryFilesAreRejectedWithoutBeingModified() throws Exception {
        byte[] invalid = {(byte) 0xc3, (byte) 0x28};
        Path invalidFile = Files.write(workspace.resolve("invalid.dat"), invalid);
        assertThat(service.readFile("invalid.dat").path("error").asString()).contains("valid UTF-8");
        assertThat(service.updateFile("invalid.dat", "replacement").has("error")).isTrue();
        assertThat(Files.readAllBytes(invalidFile)).isEqualTo(invalid);
        Path binary = Files.write(workspace.resolve("binary.dat"), new byte[]{65, 0, 66});
        assertThat(service.readFile("binary.dat").path("error").asString()).contains("binary");
        assertThat(service.updateFile("binary.dat", "replacement").has("error")).isTrue();
        assertThat(Files.readAllBytes(binary)).isEqualTo(new byte[]{65, 0, 66});
    }

    @Test
    void enforcesByteLimitsOnReadsAndWritesWithoutDamagingExistingFiles() throws Exception {
        String tooLarge = "अ".repeat(35000); // 105,000 UTF-8 bytes, despite having fewer characters.
        assertThat(service.createFile("new/large.txt", tooLarge).path("error").asString()).contains("100 KiB");
        assertThat(workspace.resolve("new")).doesNotExist();
        Files.writeString(workspace.resolve("small.txt"), "keep this");
        assertThat(service.updateFile("small.txt", tooLarge).has("error")).isTrue();
        assertThat(Files.readString(workspace.resolve("small.txt"))).isEqualTo("keep this");
        Files.writeString(workspace.resolve("large.txt"), tooLarge);
        assertThat(service.readFile("large.txt").path("error").asString()).contains("100 KiB");
        assertThat(service.updateFile("large.txt", "replacement").has("error")).isTrue();
        assertThat(Files.readString(workspace.resolve("large.txt"))).isEqualTo(tooLarge);
    }

    @Test
    void creationReportsVerificationFailureIfTheReadBackContentHasChanged() throws Exception {
        Path target = workspace.resolve("notes.txt");
        // Execute the real write, then simulate another writer changing the file before read-back.
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.newInputStream(any(Path.class), eq(LinkOption.NOFOLLOW_LINKS)))
                    .thenAnswer(invocation -> {
                        Files.writeString(target, "changed by another writer");
                        return invocation.callRealMethod();
                    });

            var result = service.createFile("notes.txt", "requested content");

            assertThat(result.path("success").asBoolean()).isFalse();
            assertThat(result.path("verified").asBoolean()).isFalse();
            assertThat(result.path("writeCompleted").asBoolean()).isTrue();
            assertThat(result.path("errorCode").asString()).isEqualTo("VERIFICATION_FAILED");
            assertThat(result.path("error").asString()).contains("write completed", "inspect it");
            assertThat(result.path("path").asString()).isEqualTo(target.toString());
        }
        assertThat(Files.readString(target)).isEqualTo("changed by another writer");
    }

    @Test
    void updateReportsVerificationFailureIfTheSavedFileCannotBeReadBack() throws Exception {
        Path target = Files.writeString(workspace.resolve("notes.txt"), "original content");
        var reads = new AtomicInteger();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.newInputStream(any(Path.class), eq(LinkOption.NOFOLLOW_LINKS)))
                    .thenAnswer(invocation -> {
                        // The pre-update validation succeeds; only the post-write read fails.
                        if (reads.incrementAndGet() == 2) throw new AccessDeniedException(target.toString());
                        return invocation.callRealMethod();
                    });

            var result = service.updateFile("notes.txt", "replacement content");

            assertThat(result.path("success").asBoolean()).isFalse();
            assertThat(result.path("verified").asBoolean()).isFalse();
            assertThat(result.path("writeCompleted").asBoolean()).isTrue();
            assertThat(result.path("errorCode").asString()).isEqualTo("VERIFICATION_FAILED");
            assertThat(reads.get()).isEqualTo(2);
        }
        assertThat(Files.readString(target)).isEqualTo("replacement content");
    }

    @Test
    void acceptsAFileExactlyAtTheByteLimit() {
        String content = "x".repeat(100 * 1024);
        var result = service.createFile("limit.txt", content);
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("verified").asBoolean()).isTrue();
        assertThat(service.readFile("limit.txt").path("content").asString()).isEqualTo(content);
    }
}
