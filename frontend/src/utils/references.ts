/** Model text is untrusted. App-generated citation markup is added only after this step. */
export function escapeModelHtml(text: string): string {
  return text.replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Preserve the public reference contract, dropping internal storage paths even from old history. */
export function sanitizeReferenceMappings(value: unknown): Record<string, Api.Chat.ReferenceEvidence> {
  if (!value || typeof value !== 'object') return {};
  const result: Record<string, Api.Chat.ReferenceEvidence> = {};
  const keys = [
    'fileMd5',
    'fileName',
    'pageNumber',
    'anchorText',
    'retrievalMode',
    'retrievalLabel',
    'retrievalQuery',
    'matchedChunkText',
    'evidenceSnippet',
    'score',
    'chunkId',
    'entryId',
    'documentType',
    'processingGeneration',
    'figureIndex',
    'figureLabel',
    'bbox',
    'caption',
    'description',
    'ocrText',
    'degraded'
  ];
  Object.entries(value).forEach(([number, raw]) => {
    if (!/^[1-9]\d*$/.test(number) || !raw || typeof raw !== 'object') return;
    const source = raw as Record<string, unknown>;
    if (typeof source.fileMd5 !== 'string') return;
    const reference: Record<string, unknown> = {};
    for (const key of keys) if (Object.hasOwn(source, key)) reference[key] = source[key];
    result[number] = reference as unknown as Api.Chat.ReferenceEvidence;
  });
  return result;
}

export function citedReferenceNumbers(text: string): number[] {
  const numbers = new Set<number>();
  for (const match of text.matchAll(/\[(\d+)\]|来源#\s*(\d+)/g)) {
    const number = Number(match[1] || match[2]);
    if (Number.isSafeInteger(number) && number > 0) numbers.add(number);
  }
  return [...numbers];
}

/** Only business identity goes into the request. Never accept imagePath or a remote URL. */
export function figureImagePath(reference: Api.Chat.ReferenceEvidence): string {
  const { fileMd5, processingGeneration, pageNumber, figureIndex } = reference;
  if (
    reference.documentType !== 'FIGURE' ||
    !/^[a-fA-F0-9]{32}$/.test(fileMd5) ||
    ![processingGeneration, pageNumber, figureIndex].every(value => Number.isSafeInteger(value) && Number(value) > 0)
  ) {
    throw new Error('Figure 引用缺少有效业务身份');
  }
  return `documents/figures/${fileMd5}/${processingGeneration}/${pageNumber}/${figureIndex}/image`;
}
