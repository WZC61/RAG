# PP-StructureV3 async client

This package only implements the hosted jobs transport. MERGED PROCESS_CONTENT PDFs now use
DocumentParsingService from the processing consumer. Figure downloads belong to the separate
persistence coordinator; this transport still downloads no Figure images.

## Configuration

`paddle.pp-structure.enabled` defaults to false. Enable with `PADDLEOCR_ENABLED`
and supply `PADDLEOCR_ACCESS_TOKEN`; credentials are never needed by unit tests.
The client bean is conditional, and constructing it does not make requests.
The model is restricted to `PP-StructureV3` in this first version.

## Protocol

Official sources audited before implementation:

- https://ai.baidu.com/ai-doc/AISTUDIO/fml7mozw5
- https://github.com/PaddlePaddle/PaddleOCR/blob/main/api_sdk/go/transport.go
- https://github.com/PaddlePaddle/PaddleOCR/blob/main/api_sdk/go/poller.go
- https://github.com/PaddlePaddle/PaddleOCR/blob/main/api_sdk/go/ocr.go

1. `submitPdf(Path)` validates a regular readable PDF, its extension, `%PDF-`
   header and the local-upload size limit. It streams a FileSystemResource in
   multipart `file`, with `model` and the JSON-string field `optionalPayload`.
2. `getStatus(jobId)` sends a Bearer-authenticated GET to `/api/v2/ocr/jobs/{jobId}`.
   HTTP status and business code are both checked, including code 11003 on HTTP 200.
3. `awaitResult(jobId)` polls immediately, then sleeps with 1.5x backoff from 3s
   to 15s by default. Its monotonic deadline covers polling and result download;
   each HTTP wait is capped by both the request timeout and remaining deadline.
   Decode/map completion is also checked against the deadline. Local CPU work is
   not forcibly interrupted by a timer; decoding checks the thread interrupt flag.
4. A done job supplies `resultUrl.jsonUrl`. A separate, unauthenticated WebClient
   fetches this HTTP(S) URL, preserving its encoded URI. Its final-write handler removes
   the automatically added Content-Length: 0 from a bodyless result GET; real BOS
   integration rejected the same signed URL with that header and accepted it without.
   The handler removes itself after one request, including on pooled connections;
   submit/poll transport is unchanged. Neither client follows redirects. No host allowlist
   is imposed. The actual response bytes are bounded, including chunked transfers.
5. The bounded body is buffered (default 32 MiB), then read as UTF-8 JSONL. Each
   nonblank line must contain `result.layoutParsingResults` as an array of objects.
   Pages are appended in wire order; one line can contain multiple pages. Missing,
   malformed or altogether empty results fail rather than produce partial output.
6. The original page objects go to PpStructureResultMapper unchanged. Each page must have an
   object prunedResult and an array parsing_res_list; an empty array is a valid blank page.
   Missing/invalid page structures or malformed required block fields fail the whole result.

There are no automatic retries, including Reactor Netty's connection-reset retry.
A submit timeout is ambiguous and must not silently create another job. Polling
failures retain jobId so a future caller can resume by calling awaitResult again.
Interruption preserves the flag. Exceptions retain stage/HTTP/business metadata,
redact the configured token, and exclude raw network/parser causes.
Remote diagnostics redact resource URLs and authorization values as well as the configured token.
WebClient's ExchangeFunctions logger stays at INFO, including in dev; other HTTP logging remains configurable.
Result HTTP failures include their status and validated BOS code/requestId when
available, but never include the raw response body, signed URL or remote message.

The client only checks PDF signature/size, not PDF validity or page count. Resource
URLs and Figure references are not durable artifacts. The persistence coordinator stores images
and metadata before PARSED; job recovery across process restarts remains unimplemented.

## Tests

Run `mvn -Dtest=PpStructureApiClientTest,PpStructureResultDecoderTest,PpStructureApiConfigurationTest,PpStructureResultMapperTest test`.
Tests use the JDK HTTP server on loopback, ephemeral ports and mock credentials.
They do not load the application or require MinIO/MySQL/Kafka/Paddle access.
