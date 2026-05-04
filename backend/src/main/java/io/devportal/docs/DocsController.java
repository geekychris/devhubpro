package io.devportal.docs;

import io.devportal.docs.dto.DocFile;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocsController {

    private final DocsService docs;

    public DocsController(DocsService docs) { this.docs = docs; }

    @GetMapping("/api/assets/{id}/docs")
    public List<DocFile> list(@PathVariable String id) throws IOException {
        return docs.list(id);
    }

    @GetMapping(value = "/api/assets/{id}/docs/file", produces = MediaType.TEXT_PLAIN_VALUE)
    public String read(@PathVariable String id, @RequestParam String path) throws IOException {
        return docs.read(id, path);
    }
}
