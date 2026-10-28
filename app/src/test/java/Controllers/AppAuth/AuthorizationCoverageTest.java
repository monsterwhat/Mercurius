package Controllers.AppAuth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 Phase 0.2 — pins the current authorization surface before any refactor.
 *
 * <p>Every class under {@code src/main/java} with a class-level {@code @Path}
 * must either carry {@code @RolesAllowed}, {@code @Authenticated} or
 * {@code @PermitAll} in its own source, or be documented in
 * {@code docs/plans/auth-allowlist.md} with a reason. The allowlist is checked
 * in both directions:</p>
 *
 * <ul>
 *   <li>an ungated resource that is not listed fails the build;</li>
 *   <li>a listed class that no longer exists, or that has been annotated,
 *       also fails — so the file shrinks instead of rotting.</li>
 * </ul>
 *
 * <p>This is a source-scanning test by design (same idiom as
 * {@link StaticAssetContractTest}): it needs no container, and it catches a
 * new resource the moment it is added, before any request is made.</p>
 */
class AuthorizationCoverageTest {

    private static final Pattern CLASS_PATH = Pattern.compile("(?m)^@Path\\b");
    private static final Pattern CLASS_GATE =
            Pattern.compile("(?m)^@(RolesAllowed|Authenticated|PermitAll)\\b");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+);");
    private static final Pattern ALLOWLIST_ENTRY = Pattern.compile("^- `([A-Za-z_][\\w.]*)`", Pattern.MULTILINE);

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Path ALLOWLIST = Path.of("../docs/plans/auth-allowlist.md");

    @Test
    void everyResourceIsGatedOrDocumented() throws IOException {
        Set<String> allowlist = readAllowlist();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!CLASS_PATH.matcher(source).find() || CLASS_GATE.matcher(source).find()) {
                    continue;
                }
                String fqcn = fullyQualifiedName(source, file);
                if (!allowlist.contains(fqcn)) {
                    offenders.add(fqcn);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "Ungated @Path classes must carry a role annotation or be listed with a reason in "
                        + ALLOWLIST.toAbsolutePath().normalize() + ": " + offenders);
    }

    @Test
    void allowlistHasNoStaleEntries() throws IOException {
        List<String> stale = new ArrayList<>();

        for (String fqcn : readAllowlist()) {
            Path source = SOURCE_ROOT.resolve(fqcn.replace('.', '/') + ".java");
            if (!Files.isRegularFile(source)) {
                stale.add(fqcn + " (source file not found)");
                continue;
            }
            if (CLASS_GATE.matcher(Files.readString(source)).find()) {
                stale.add(fqcn + " (now annotated: remove its allowlist line)");
            }
        }

        assertTrue(stale.isEmpty(),
                "Authorization allowlist entries that should be removed: " + stale);
    }

    private static Set<String> readAllowlist() throws IOException {
        assertTrue(Files.isRegularFile(ALLOWLIST),
                "Missing authorization allowlist: " + ALLOWLIST.toAbsolutePath().normalize());
        Set<String> entries = new LinkedHashSet<>();
        Matcher matcher = ALLOWLIST_ENTRY.matcher(Files.readString(ALLOWLIST));
        while (matcher.find()) {
            entries.add(matcher.group(1));
        }
        return entries;
    }

    private static String fullyQualifiedName(String source, Path file) {
        Matcher pkg = PACKAGE.matcher(source);
        String simpleName = file.getFileName().toString().replace(".java", "");
        return pkg.find() ? pkg.group(1) + "." + simpleName : simpleName;
    }
}
