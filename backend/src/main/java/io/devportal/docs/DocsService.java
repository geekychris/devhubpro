package io.devportal.docs;

import io.devportal.asset.AssetRepository;
import io.devportal.asset.error.ConflictException;
import io.devportal.asset.error.NotFoundException;
import io.devportal.docs.dto.DocFile;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Discover and read Markdown / text docs in an asset's workspace. */
@Service
public class DocsService {

    private static final long MAX_DOC_BYTES = 1_000_000; // 1 MB
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".gradle", ".idea", ".vscode",
        "vendor", "out", ".mvn"
    );

    private final AssetRepository assets;
    private final WorkspaceService workspace;

    public DocsService(AssetRepository assets, WorkspaceService workspace) {
        this.assets = assets;
        this.workspace = workspace;
    }

    public List<DocFile> list(String assetId) throws IOException {
        Path ws = ensureWorkspace(assetId);
        List<DocFile> out = new ArrayList<>();
        Files.walkFileTree(ws, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(ws) && SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.toLowerCase().endsWith(".md")) {
                    out.add(new DocFile(
                        ws.relativize(file).toString(),
                        name,
                        attrs.size()
                    ));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // sort: README first, then alphabetical
        out.sort((a, b) -> {
            boolean ra = "README.md".equalsIgnoreCase(a.name());
            boolean rb = "README.md".equalsIgnoreCase(b.name());
            if (ra && !rb) return -1;
            if (rb && !ra) return 1;
            return a.path().compareTo(b.path());
        });
        return out;
    }

    /** Read a doc by its workspace-relative path. Path-traversal-safe. */
    public String read(String assetId, String relativePath) throws IOException {
        Path ws = ensureWorkspace(assetId);
        Path resolved = ws.resolve(relativePath).normalize();
        if (!resolved.startsWith(ws)) {
            throw new ConflictException("Path traversal not allowed");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new NotFoundException("Doc '" + relativePath + "' not found in '" + assetId + "'");
        }
        long size = Files.size(resolved);
        if (size > MAX_DOC_BYTES) {
            throw new ConflictException("Doc too large (" + size + " bytes); max " + MAX_DOC_BYTES);
        }
        return Files.readString(resolved);
    }

    private Path ensureWorkspace(String assetId) {
        if (!assets.existsById(assetId)) throw new NotFoundException("Asset '" + assetId + "' not found");
        Path ws = workspace.workspaceFor(assetId);
        if (!Files.isDirectory(ws.resolve(".git"))) {
            throw new ConflictException("Workspace empty for '" + assetId + "' — clone first");
        }
        return ws;
    }
}
