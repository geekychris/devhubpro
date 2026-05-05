package io.devportal.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed search query: a list of non-empty terms plus a mode flag.
 *
 * <p>Tokens are split on whitespace. A token equal to {@code AND} (case-insensitive) flips the
 * combinator to AND for the entire query and is itself dropped. Default is OR — i.e. a query of
 * "spring boot" matches assets that mention either word; "spring AND boot" requires both.
 *
 * <p>Empty / whitespace-only inputs yield {@code terms = []} and the caller should treat the
 * query as "no filter."
 */
public record SearchQuery(List<String> terms, boolean and) {

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    public static SearchQuery parse(String raw) {
        if (raw == null || raw.isBlank()) return new SearchQuery(List.of(), false);
        String[] tokens = raw.trim().split("\\s+");
        List<String> terms = new ArrayList<>();
        boolean and = false;
        for (String t : tokens) {
            if (t.equalsIgnoreCase("AND")) { and = true; continue; }
            if (t.equalsIgnoreCase("OR")) continue; // OR is the default; tolerate it as a no-op marker
            if (!t.isBlank()) terms.add(t);
        }
        return new SearchQuery(terms, and && terms.size() > 1);
    }
}
