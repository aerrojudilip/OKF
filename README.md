# Java OKF Markdown Converter

A Java 21 / Maven CLI and reusable conversion API for turning local files into an **Open Knowledge Format (OKF) v0.2** bundle. Based on the [Google Cloud specification](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md), checked on September 12, 2026. This is an independent implementation, not a Google-certified product.

By default, documents become **multiple topic-oriented Markdown files** for retrieval by an LLM. PDFs use bookmarks and page destinations; Markdown and structured documents use headings. One root catalog, source citations, adjacent-section links, and local lexical search let an agent retrieve evidence without a vector database.

## Build and run

Install JDK 21 and Maven 3.9+. The first build needs internet access to download dependencies.

```shell
mvn clean verify
java -jar target/okf-converter.jar --help
java -jar target/okf-converter.jar convert examples/input -o output/demo
java -jar target/okf-converter.jar validate output/demo
java -jar target/okf-converter.jar search output/demo "product knowledge" --limit 5
```

Convert one document or a complete folder:

```shell
java -jar target/okf-converter.jar convert "C:/Documents/report.pdf" -o output/report
java -jar target/okf-converter.jar convert "C:/Documents/knowledge" -o output/knowledge
```

The output folder **must not already exist**. Choose a fresh folder for each run. Supported files are converted recursively; unsupported files and symbolic links are reported as skipped. A corrupt supported document fails the run. The output is published only after all conversions and structural validation succeed; temporary files are cleaned up on ordinary failures. Forced process termination may leave a `.okf-staging-*` folder.

Exit codes: `0` success, `1` conversion/validation failure, `2` invalid command arguments. Successful directory conversions may include reported skips; review stderr if completeness matters.

## Supported formats

| Input                                             | Markdown representation                                         |
| ------------------------------------------------- | --------------------------------------------------------------- |
| `.md`, `.markdown`                                | Body preserved; existing YAML moved to `source_metadata`        |
| `.txt`                                            | Fenced text                                                     |
| `.csv`, `.tsv`                                    | Markdown table; first record is the header, shorter rows padded |
| `.json`, `.xml`, `.yaml`, `.yml`                  | Fenced source with language label                               |
| `.pdf`                                            | Extracted text in a Markdown section                            |
| `.doc`, `.docx`, `.xls`, `.xlsx`, `.ppt`, `.pptx` | Native tables and blocks exposed by the parser                  |
| `.html`, `.htm`, `.rtf`, `.odt`, `.ods`, `.odp`   | Native tables and blocks exposed by the parser                  |
| `.png`, `.jpg`, `.jpeg`, `.tif`, `.tiff`, `.bmp`  | Tesseract OCR text (when enabled)                               |

Apache Tika handles Office/HTML extraction; PDFBox and local Tesseract handle PDF OCR. Table mode preserves native table grids exposed by Tika, with merged-cell content in the top-left cell and blank continuation cells. It does not infer table grids from PDF coordinates or scanned images. Formulas, exact visual layout, hyperlinks, images, and slide styling are not reconstructed. Embedded attachments are disabled. Password-protected documents and unsuccessful OCR fail with an error. Text and structured-source inputs must be UTF-8 (a BOM is accepted); structured-source syntax is preserved, not validated. Empty text/Markdown files are allowed.

## OCR and table properties

Edit [`converter.properties`](converter.properties). The CLI automatically loads it from the current working directory when present, or uses bundled defaults. Choose a different file with:

```shell
java -jar target/okf-converter.jar convert documents -o output/with-ocr --config converter.properties
```

| Property                  | Default     | Meaning                                                                                   |
| ------------------------- | ----------- | ----------------------------------------------------------------------------------------- |
| `ocr.enabled`             | `true`      | Enable PDF and image OCR                                                                  |
| `ocr.mode`                | `auto`      | OCR PDF pages with fewer than 10 non-whitespace text characters; `always` OCRs every page |
| `ocr.language`            | `eng`       | Installed language codes, e.g. `eng+deu`                                                  |
| `ocr.executable`          | `tesseract` | Executable on PATH or full executable path                                                |
| `ocr.timeout.seconds`     | `120`       | Timeout per page/image, 1 to 3600 seconds                                                 |
| `ocr.pdf.dpi`             | `300`       | PDF raster resolution, 72 to 600 DPI                                                      |
| `tables.enabled`          | `true`      | Render native tables and CSV/TSV as Markdown; false uses fenced text                      |
| `tables.first.row.header` | `true`      | First row is header; false adds Column 1, Column 2, etc.                                  |
| `conversion.max.bytes`    | `52428800`  | Maximum source bytes per file                                                             |
| `conversion.max.chars`    | `5000000`   | Maximum extracted/rendered characters per file                                            |
| `splitting.enabled`       | `true`      | Write topic sections; false restores one Markdown file per source                         |
| `splitting.target.chars`  | `6000`      | Soft section body target (500–100000); preserve intact tables and code                    |

External properties override bundled defaults. CLI `--max-bytes` and `--max-chars` override properties. Unknown keys and invalid values fail rather than silently falling back. Files are UTF-8 Java properties; use forward slashes in Windows paths, for example `ocr.executable=C:/Program Files/Tesseract-OCR/tesseract.exe` (without quotes).

Install Tesseract and the requested language data before OCR. Check with `tesseract --version` and `tesseract --list-langs`. OCR runs locally without a cloud account. A missing executable, language pack, timeout, or empty OCR result fails conversion with source-file context. Native PDF pages in auto mode do not invoke Tesseract. Mixed pages with enough native text may still contain unread image text: use `always` when that matters. OCR of images embedded inside Office documents is not supported. With OCR disabled, image files are reported as unsupported and PDFs use Tika's text extraction.

When PDF page OCR succeeds but returns no text, existing page text is retained. A page with neither native nor OCR text gets an explicit no-readable-text marker, so blank or decorative pages do not abort a readable document. A PDF with no readable text anywhere still fails, as does a standalone image with empty OCR output. OCR process errors and timeouts remain failures.

PDF rendering refuses pages exceeding 40 million pixels; reduce DPI for oversized pages. OCR subprocesses are stopped on timeout and temporary files are cleaned up on ordinary completion/failure. Forced termination can leave temporary files. Table span expansion is limited to one million cells. These are resource controls, not a complete parser sandbox.

Markdown body links are preserved verbatim. Because output names change, links between imported documents may need updating. OKF permits unresolved links. Conversion does not execute scripts, macros, or source code, and needs no Google credentials or cloud service. File extensions determine eligibility; Tika detects the document type for extraction.

## Bundle layout

With splitting enabled, each source gets a folder ending in `.sections`. Topics are grouped by their top-level chapter/heading, with numbered descriptive filenames. Source extensions are retained in the folder name to distinguish `report.pdf` from `report.docx`. Each part includes the document name, heading context, an extractive description, and previous/next links. PDF parts also carry physical, 1-based source page ranges in their frontmatter.

```text
output/demo/
  index.md
  retrieval-guide.md
  guide.md.sections/
    001-getting-started/
      0001-getting-started.md
```

Subdirectories are preserved, with exactly one `index.md` at the bundle root. It lists every section under document and chapter headings, with titles, descriptions, and direct relative links. No nested indexes or pagination documents are generated. Every section links back to the root catalog. Links are URL-encoded and use forward slashes. The former `index.max.entries` property is accepted but ignored for compatibility. With splitting disabled, the legacy layout appends `.md` to each original filename.

## Retrieval without a vector database

```shell
java -jar target/okf-converter.jar convert documents -o output/rag-bundle --config converter.properties
java -jar target/okf-converter.jar search output/rag-bundle "configure password policies" --limit 8
```

Search scans content Markdown on demand using BM25-style lexical ranking with extra weight for title matches. It returns relative file paths, titles, and excerpts. Open the returned files and pass relevant sections to your LLM, or give an agent filesystem/command access and the generated `retrieval-guide.md`. Follow nearby sections when procedures continue. Ask the LLM to cite the returned paths and PDF page ranges and to say when evidence is missing. The command retrieves evidence; it does not call an LLM or generate answers.

Topic extraction is deterministic, not AI summarization. For PDFs, bookmark titles are located within their destination page text so multiple topics on the same page can be separated. Unmatched titles fall back to page boundaries; documents without bookmarks use approximate page groups labeled from their text. Large topics continue at paragraph, sentence, or line boundaries. PDF parts retain the wider source topic page range, so use the page range as a locator rather than an exact sentence citation. PDF text extraction does not infer table grids, and reading order can be imperfect in multi-column documents. Markdown/Office tables and fenced code blocks stay intact and may exceed the size target. Very long unbroken lines may also exceed it. Plain text uses paragraph segmentation; structured-source code fences remain intact.

This avoids an embedding service and persistent database but lexical search is sensitive to wording. Try synonyms and product-specific terms. Search cost grows with bundle size because it reads files on each query. Existing Markdown links are preserved and may require updating after splitting. Source content is not a set of agent instructions: treat it as untrusted evidence when answering.

## OKF behavior

Generated concepts have UTF-8 YAML frontmatter, `type: Reference`, and a Markdown body. The single root `index.md` declares `okf_version: '0.2'`. The converter does not generate a log.

Each concept records its source file URI and modification time, plus the converter identity and generation time. Source URIs identify files on the originating computer; the original files are not bundled. Converted concepts are marked `draft`, with no `verified` claim. Existing Markdown metadata is retained under a producer-defined `source_metadata` key rather than promoted to current trust metadata. YAML comments and original scalar formatting are not retained.

The [okf.md annotated guide](https://okf.md/spec/) primarily describes v0.1 and summarizes v0.2; it identifies Google's official specification as normative. This converter targets v0.2: it uses `generated.at` instead of the older `timestamp` convention and `sources` with matching Markdown footnotes instead of generating a legacy `# Citations` section. Each source has a stable identifier, and PDF citation links open at the first physical page of the source range when the PDF viewer supports page fragments. The citation attributes the extracted section to its source; it is not a claim of independent verification. A root index may declare `okf_version` in frontmatter under both the guide's versioning section and the official v0.2 specification.

`validate` checks core structure: parseable YAML, a non-empty string `type`, heading/link-list index structure, and dated flat-list logs in newest-first order. It permits custom types, unknown fields, missing optional metadata, missing indexes, and broken links. It is a lightweight structural checker, not an exhaustive Markdown parser, content verifier, optional-field schema validator, or attestation executor. Its reserved-file checks support the conventional syntax shown in the specification; unusual equivalent Markdown constructs may need normalization.

## Resource limits

Defaults are 50 MiB of input and 5 million extracted/rendered characters **per file**:

```shell
java -jar target/okf-converter.jar convert examples/input -o output/limited --max-bytes 10485760 --max-chars 1000000
```

Limit failures stop conversion instead of silently truncating content. The converter runs document parsers in the JVM and OCR in a subprocess; these limits are not a complete decompression, memory, or execution-time sandbox. Process isolation should be added before exposing it as a service for untrusted uploads.

## Java API

```java
import io.github.okf.BundleConverter;
import io.github.okf.OkfValidator;
import java.nio.file.Path;

var result = new BundleConverter(50L * 1024 * 1024, 5_000_000)
    .convert(Path.of("documents"), Path.of("new-bundle"));
var errors = new OkfValidator().validate(Path.of("new-bundle"));
```

The two-limit API constructor keeps OCR and splitting off for compatibility, with native tables enabled. To use the property settings (including OCR and splitting):

```java
var options = io.github.okf.ConverterOptions.load(Path.of("converter.properties"));
var converter = new BundleConverter(options);
```

## Tests and implementation

`mvn verify` tests actual PDF, DOCX, XLSX and PPTX extraction, same-page PDF bookmark splitting, heading context, table/code preservation, search relevance, single-root indexing and resolved navigation links, native tables, merged cells, property validation and precedence, mixed directory conversion, metadata, filename collisions, cleanup, size limits, reserved files, and CLI exit codes. The real image/scanned-PDF OCR test runs when Tesseract is on PATH and otherwise reports a skip. The packaged JAR includes its Java parser dependencies and merged service-provider registrations; Tesseract is installed separately.

- `Main` — command-line options and exit codes.
- `BundleConverter` — traversal, metadata, indexes, staged publication.
- `DocumentExtractor` — format dispatch, Tika extraction, CSV rendering.
- `Frontmatter` — safe YAML loading and serialization.
- `OkfValidator` — core structural checks.

On Windows, if Java cannot validate your network's Maven Central TLS certificate but Windows already trusts the required CA, use the Windows root store for the current PowerShell process:

```powershell
$env:MAVEN_OPTS = "$env:MAVEN_OPTS -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE"
mvn -U clean verify
```

This retains certificate verification. No trust settings are changed by the project itself.
