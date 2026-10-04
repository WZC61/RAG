# PP-StructureV3 fixture

`pp-structure-sample.json` is trimmed from the user-provided real response:
`Multimodal Prompt Learning with Missing Modalities for Sentiment.pdf_by_PP-StructrueV3.json`.

It contains original pages 1, 3, 4, 6 and 7, in that order. There is no explicit page number
in this response, so the mapper assigns fixture page numbers 1 through 5.

Preserved fields:

- `prunedResult.parsing_res_list` (original labels, HTML/text, bounding boxes, IDs and orders)
- `prunedResult.overall_ocr_res.rec_texts` and `rec_boxes`
- `markdown.images` (original keys)

Temporary image URLs have been replaced with deterministic `https://example.invalid/pp-structure/`
URLs. No signed URL, access token or downloaded image is stored. Tests never fetch these URLs.
Model settings, layout detection, visualization images and other unused data are omitted.

The source genuinely includes null `block_order`, two side-by-side figures, an image/chart
and `Table 1`/`Table 2` misclassified as `figure_title`. Chart tests use an original chart,
not a relabeled synthetic image. Synthetic test pages supplement geometry and malformed-field cases.
