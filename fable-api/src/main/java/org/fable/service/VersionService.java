package org.fable.service;

import lombok.extern.slf4j.Slf4j;
import org.fable.model.dto.ReleaseNote;
import org.fable.model.dto.VersionInfo;
import org.fable.service.network.OutboundHttpSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class VersionService {

    @Value("${app.version:unknown}")
    String appVersion;

    @Value("${app.version.github-repo:opensourcefan/Fable}")
    String githubRepo;

    private static final String GITHUB_API_BASE = "https://api.github.com/repos/";
    private static final String GITHUB_TAGS_REF_PREFIX = "refs/tags/";
    private static final int MAX_RELEASES = 15;
    private static final Pattern VERSION_TAG_PATTERN = Pattern.compile("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");
    private static final RestClient REST_CLIENT = RestClient.builder()
            .requestFactory(OutboundHttpSupport.buildTimeoutRequestFactory(
                    OutboundHttpSupport.VERSION_CONNECT_TIMEOUT,
                    OutboundHttpSupport.VERSION_READ_TIMEOUT))
            .defaultHeader("Accept", "application/vnd.github+json")
            .defaultHeader("User-Agent", "Fable-Version-Checker")
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();


    public VersionInfo getVersionInfo() {
        String latest = "unknown";
        try {
            latest = fetchLatestGitHubReleaseVersion();
        } catch (Exception e) {
            log.warn("Error fetching latest release version");
        }
        return new VersionInfo(appVersion, latest);
    }

    public List<ReleaseNote> getChangelogSinceCurrentVersion() {
        return fetchReleaseNotesSince(appVersion);
    }


    public String fetchLatestGitHubReleaseVersion() {
        String latestTag = fetchLatestGitHubTagVersion();
        if (latestTag != null) {
            return latestTag;
        }

        String latestReleaseTag = fetchLatestGitHubReleaseTagVersion();
        if (latestReleaseTag != null) {
            return latestReleaseTag;
        }

        return "unknown";
    }

    private String fetchLatestGitHubReleaseTagVersion() {
        try {
            String response = REST_CLIENT.get()
                    .uri(getGithubApiBaseUri() + "/releases?per_page=" + MAX_RELEASES)
                    .retrieve()
                    .body(String.class);

            JsonNode releases = MAPPER.readTree(response);
            if (!releases.isArray()) {
                return null;
            }

            List<String> releaseTags = new ArrayList<>();
            for (JsonNode release : releases) {
                String tag = release.path("tag_name").asText(null);
                if (tag != null && !tag.isBlank()) {
                    releaseTags.add(tag);
                }
            }

            return selectHighestVersionTag(releaseTags);

        } catch (Exception e) {
            log.warn("Failed to fetch latest release version");
        }

        return null;
    }

    private String fetchLatestGitHubTagVersion() {
        try {
            String response = REST_CLIENT.get()
                    .uri(getGithubApiBaseUri() + "/git/matching-refs/tags/")
                    .retrieve()
                    .body(String.class);

            JsonNode refs = MAPPER.readTree(response);
            if (!refs.isArray()) {
                return null;
            }

            List<String> tags = new ArrayList<>();
            for (JsonNode refNode : refs) {
                String tag = extractTagName(refNode.path("ref").asText(null));
                if (tag != null) {
                    tags.add(tag);
                }
            }

            return selectHighestVersionTag(tags);
        } catch (Exception e) {
            log.warn("Failed to fetch latest tag version");
        }

        return null;
    }

    public List<ReleaseNote> fetchReleaseNotesSince(String currentVersion) {
        if ("development".equals(currentVersion)) {
            log.warn("Skipping fetch of release notes because current version is '{}', which is a local development build.", currentVersion);
            return new ArrayList<>();
        }
        log.info("Fetching release notes since version: {}", currentVersion);

        List<ReleaseNote> updates = new ArrayList<>();
        try {
            String response = REST_CLIENT.get()
                    .uri(getGithubApiBaseUri() + "/releases?per_page=" + MAX_RELEASES)
                    .retrieve()
                    .body(String.class);

            JsonNode releases = MAPPER.readTree(response);
            if (!releases.isArray()) {
                log.warn("Invalid releases response from GitHub API");
                return updates;
            }

            for (JsonNode release : releases) {
                String tag = release.path("tag_name").asText(null);
                if (tag == null || !isVersionGreater(tag, currentVersion)) {
                    continue;
                }
                String url = "https://github.com/" + githubRepo + "/releases/tag/" + tag;
                LocalDateTime published = LocalDateTime.parse(release.path("published_at").asText(), DateTimeFormatter.ISO_DATE_TIME);
                updates.add(new ReleaseNote(tag, release.path("name").asText(tag), release.path("body").asText(""), url, published));
            }

            log.info("Returning {} newer releases", updates.size());

        } catch (Exception e) {
            log.error("Failed to fetch release notes", e);
        }

        return updates;
    }

    private boolean isVersionGreater(String version1, String version2) {
        VersionTag parsedVersion1 = parseVersionTag(version1);
        VersionTag parsedVersion2 = parseVersionTag(version2);
        if (parsedVersion1 == null || parsedVersion2 == null) {
            return false;
        }

        return parsedVersion1.compareTo(parsedVersion2) > 0;
    }

    private String getGithubApiBaseUri() {
        return GITHUB_API_BASE + githubRepo;
    }

    private String extractTagName(String ref) {
        if (ref == null || ref.isBlank() || !ref.startsWith(GITHUB_TAGS_REF_PREFIX)) {
            return null;
        }

        String tag = ref.substring(GITHUB_TAGS_REF_PREFIX.length());
        if (tag.endsWith("^{}")) {
            tag = tag.substring(0, tag.length() - 3);
        }

        return tag.isBlank() ? null : tag;
    }

    private String selectHighestVersionTag(List<String> candidates) {
        return new LinkedHashSet<>(candidates).stream()
                .map(this::parseVersionTag)
                .filter(Objects::nonNull)
                .max(VersionTag::compareTo)
                .map(VersionTag::tagValue)
                .orElse(null);
    }

    private VersionTag parseVersionTag(String version) {
        if (version == null) {
            return null;
        }

        String trimmedVersion = version.trim();
        Matcher matcher = VERSION_TAG_PATTERN.matcher(trimmedVersion);
        if (!matcher.matches()) {
            return null;
        }

        String preRelease = matcher.group(4);
        String buildMetadata = matcher.group(5);
        List<VersionIdentifier> preReleaseIdentifiers = new ArrayList<>();
        if (preRelease != null && !preRelease.isBlank()) {
            for (String identifier : preRelease.split("\\.")) {
                preReleaseIdentifiers.add(VersionIdentifier.from(identifier));
            }
        }

        return new VersionTag(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                preReleaseIdentifiers,
                trimmedVersion
        );
    }

    private record VersionTag(int major, int minor, int patch, List<VersionIdentifier> preReleaseIdentifiers, String tagValue) implements Comparable<VersionTag> {
        @Override
        public int compareTo(VersionTag other) {
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }

            int minorComparison = Integer.compare(minor, other.minor);
            if (minorComparison != 0) {
                return minorComparison;
            }

            int patchComparison = Integer.compare(patch, other.patch);
            if (patchComparison != 0) {
                return patchComparison;
            }

            boolean hasPreRelease = !preReleaseIdentifiers.isEmpty();
            boolean otherHasPreRelease = !other.preReleaseIdentifiers.isEmpty();
            if (!hasPreRelease && !otherHasPreRelease) {
                return 0;
            }
            if (!hasPreRelease) {
                return 1;
            }
            if (!otherHasPreRelease) {
                return -1;
            }

            int maxIdentifiers = Math.max(preReleaseIdentifiers.size(), other.preReleaseIdentifiers.size());
            for (int index = 0; index < maxIdentifiers; index++) {
                if (index >= preReleaseIdentifiers.size()) {
                    return -1;
                }
                if (index >= other.preReleaseIdentifiers.size()) {
                    return 1;
                }

                int identifierComparison = preReleaseIdentifiers.get(index).compareTo(other.preReleaseIdentifiers.get(index));
                if (identifierComparison != 0) {
                    return identifierComparison;
                }
            }

            return 0;
        }
    }

    private record VersionIdentifier(String value, Integer numericValue) implements Comparable<VersionIdentifier> {
        private static VersionIdentifier from(String value) {
            if (value.chars().allMatch(Character::isDigit)) {
                return new VersionIdentifier(value, Integer.parseInt(value));
            }

            return new VersionIdentifier(value, null);
        }

        @Override
        public int compareTo(VersionIdentifier other) {
            if (numericValue != null && other.numericValue != null) {
                return Integer.compare(numericValue, other.numericValue);
            }
            if (numericValue != null) {
                return -1;
            }
            if (other.numericValue != null) {
                return 1;
            }
            return value.compareTo(other.value);
        }
    }
}