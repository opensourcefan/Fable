package org.fable.service.kobo;

import org.fable.util.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KepubConversionService {

    @Autowired
    private FileService fileService;

    private static final String KEPUBIFY_GITHUB_BASE_URL = "https://github.com/fable-app/fable-tools/raw/main/kepubify/";

    /** Maximum time kepubify is allowed to run before the process is killed. */
    private static final long KEPUBIFY_TIMEOUT_MINUTES = 10;

    private static final String BIN_DARWIN_ARM64 = "kepubify-darwin-arm64";
    private static final String BIN_DARWIN_X64 = "kepubify-darwin-64bit";
    private static final String BIN_LINUX_X64 = "kepubify-linux-64bit";
    private static final String BIN_LINUX_X86 = "kepubify-linux-32bit";
    private static final String BIN_LINUX_ARM = "kepubify-linux-arm";
    private static final String BIN_LINUX_ARM64 = "kepubify-linux-arm64";

    /**
     * Known SHA-256 digests for each kepubify binary variant (pgaskin/kepubify v4.0.4).
     * These are verified against the upstream release assets and must be updated whenever
     * the binaries in fable-tools are upgraded to a new kepubify version.
     *
     * SECURITY (OWASP A08): Verifying the digest before executing the binary prevents
     * supply-chain attacks where a DNS hijack, BGP hijack, or repository compromise could
     * deliver a malicious executable in place of the real kepubify binary.
     */
    private static final Map<String, String> KNOWN_SHA256 = Map.of(
        BIN_DARWIN_ARM64,  "6467d44439ee899113c7f710b509ef9e5ce65e8df711c85192b9ea5b683594b7",
        BIN_DARWIN_X64,    "851afab0b83ecaf11f6965c901483eed3e74a6b41a3ab0a68f7321bc48bac4a3",
        BIN_LINUX_X64,     "37d7628d26c5c906f607f24b36f781f306075e7073a6fe7820a751bb60431fc5",
        BIN_LINUX_X86,     "3365a848ce06d43fca8f1999eb69c6c8e0e20a56b6b8658a8466b9726adef0f5",
        BIN_LINUX_ARM,     "07f23275c4e674093443f01a591aa0980b0b87dbb0a10986d5001e9d56b0e1e7",
        BIN_LINUX_ARM64,   "5a15b8f6f6a96216c69330601bca29638cfee50f7bf48712795cff88ae2d03a3"
    );

    public File convertEpubToKepub(File epubFile, File tempDir, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        validateInputs(epubFile);

        Path kepubifyBinary = setupKepubifyBinary();
        File outputFile = executeKepubifyConversion(epubFile, tempDir, kepubifyBinary, forceEnableHyphenation);

        log.info("Successfully converted {} to {} (size: {} bytes)", epubFile.getName(), outputFile.getName(), outputFile.length());
        return outputFile;
    }

    private void validateInputs(File epubFile) {
        if (epubFile == null || !epubFile.isFile() || !epubFile.getName().endsWith(".epub")) {
            throw new IllegalArgumentException("Invalid EPUB file: " + epubFile);
        }
    }

    private Path setupKepubifyBinary() throws IOException {
        String binaryName = getKepubifyBinaryName();
        String toolsDirPath = fileService.getToolsKepubifyPath();
        Path toolsDir = Paths.get(toolsDirPath);
        if (!Files.exists(toolsDir)) {
            Files.createDirectories(toolsDir);
        }
        Path binaryPath = toolsDir.resolve(binaryName);

        if (!Files.exists(binaryPath)) {
            String downloadUrl = KEPUBIFY_GITHUB_BASE_URL + binaryName;
            log.info("Downloading kepubify binary '{}' from {}", binaryName, downloadUrl);
            Path tempPath = toolsDir.resolve(binaryName + ".tmp");
            try (InputStream in = org.fable.service.network.OutboundHttpSupport.openBoundedUrlStream(
                    downloadUrl,
                    org.fable.service.network.OutboundHttpSupport.CONNECT_TIMEOUT,
                    org.fable.service.network.OutboundHttpSupport.BINARY_DOWNLOAD_READ_TIMEOUT)) {
                Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
            }
            verifyBinaryIntegrity(tempPath, binaryName);
            Files.move(tempPath, binaryPath, StandardCopyOption.REPLACE_EXISTING);
            if (!binaryPath.toFile().setExecutable(true)) {
                log.warn("Failed to set executable permission for '{}'", binaryPath.toAbsolutePath());
            }
            log.info("Downloaded and verified kepubify binary at {}", binaryPath.toAbsolutePath());
        } else {
            if (!binaryPath.toFile().setExecutable(true)) {
                log.warn("Failed to set executable permission for '{}'", binaryPath.toAbsolutePath());
            }
            log.debug("Using existing kepubify binary at {}", binaryPath.toAbsolutePath());
        }
        return binaryPath;
    }

    /**
     * Verifies the SHA-256 digest of the downloaded binary against the known-good value.
     * Deletes the file and throws if the digest does not match (OWASP A08 – Software Integrity).
     */
    private void verifyBinaryIntegrity(Path filePath, String binaryName) throws IOException {
        String expectedHex = KNOWN_SHA256.get(binaryName);
        if (expectedHex == null) {
            log.warn("No known SHA-256 digest for kepubify binary '{}' — skipping integrity check. " +
                    "Please add the expected digest to KepubConversionService.KNOWN_SHA256.", binaryName);
            return;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(filePath);
            byte[] digestBytes = md.digest(fileBytes);
            String actualHex = HexFormat.of().formatHex(digestBytes);
            if (!expectedHex.equalsIgnoreCase(actualHex)) {
                Files.deleteIfExists(filePath);
                throw new IOException(
                    "kepubify binary integrity check FAILED for '" + binaryName + "'. " +
                    "Expected SHA-256: " + expectedHex + " — Got: " + actualHex + ". " +
                    "The downloaded binary has been deleted. This may indicate a supply-chain compromise."
                );
            }
            log.info("kepubify binary '{}' passed SHA-256 integrity check.", binaryName);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available for integrity check", e);
        }
    }

    private String getKepubifyBinaryName() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        log.debug("Detected OS: {} ({})", osName, osArch);

        if (osName.contains("mac") || osName.contains("darwin")) {
            if (osArch.contains("arm") || osArch.contains("aarch64")) {
                return BIN_DARWIN_ARM64;
            } else {
                return BIN_DARWIN_X64;
            }
        } else if (osName.contains("linux")) {
            if (osArch.contains("arm64") || osArch.contains("aarch64")) {
                return BIN_LINUX_ARM64;
            } else if (osArch.contains("arm")) {
                return BIN_LINUX_ARM;
            } else if (osArch.contains("64")) {
                return BIN_LINUX_X64;
            } else if (osArch.contains("86")) {
                return BIN_LINUX_X86;
            }
        }
        throw new IllegalStateException("Unsupported operating system or architecture: " + osName + " / " + osArch);
    }

    private File executeKepubifyConversion(File epubFile, File tempDir, Path kepubifyBinary, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        ProcessBuilder pb;

        if (forceEnableHyphenation)
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "--hyphenate", "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());
        else
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());

        pb.directory(tempDir);

        log.info("Starting kepubify conversion for {} -> output dir: {}", epubFile.getAbsolutePath(), tempDir.getAbsolutePath());

        Process process = pb.start();

        // Drain stdout and stderr concurrently to prevent pipe-buffer deadlock
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(
                () -> readProcessOutput(process.getInputStream()));
        CompletableFuture<String> errorFuture = CompletableFuture.supplyAsync(
                () -> readProcessOutput(process.getErrorStream()));

        try {
            if (!process.waitFor(KEPUBIFY_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Kepubify conversion timed out after " + KEPUBIFY_TIMEOUT_MINUTES
                        + " minutes for: " + epubFile.getName());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Kepubify conversion interrupted for: " + epubFile.getName(), e);
        }

        int exitCode = process.exitValue();
        String output = outputFuture.getNow("");
        String error  = errorFuture.getNow("");
        logProcessResults(exitCode, output, error);

        if (exitCode != 0) {
            throw new IOException(String.format("Kepubify conversion failed with exit code: %d. Error: %s", exitCode, error));
        }

        return findOutputFile(tempDir);
    }

    private String readProcessOutput(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Error reading process output: {}", e.getMessage());
            return "";
        }
    }

    private void logProcessResults(int exitCode, String output, String error) {
        log.debug("Kepubify process exited with code {}", exitCode);
        if (!output.isEmpty()) {
            log.debug("Kepubify stdout: {}", output);
        }
        if (!error.isEmpty()) {
            log.error("Kepubify stderr: {}", error);
        }
    }

    private File findOutputFile(File tempDir) throws IOException {
        File[] kepubFiles = tempDir.listFiles((dir, name) -> name.endsWith(".kepub.epub"));
        if (kepubFiles == null || kepubFiles.length == 0) {
            throw new IOException("Kepubify conversion completed but no .kepub.epub file was created in: " + tempDir.getAbsolutePath());
        }
        return kepubFiles[0];
    }
}
