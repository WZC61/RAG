import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import MarkdownIt from 'markdown-it';
import { setImmediate } from 'node:timers/promises';

const referenceSource = await readFile(new URL('../src/utils/references.ts', import.meta.url), 'utf8');
const refs = {};
vm.runInNewContext(ts.transpileModule(referenceSource, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, { exports: refs });
const figure = { fileMd5: 'f3845f9977bd2f0f31db7ca1100546d2', documentType: 'FIGURE', processingGeneration: 1,
  pageNumber: 4, figureIndex: 2, fileName: 'paper.pdf', figureLabel: 'Figure 3', caption: 'caption', description: 'description' };

test('model HTML cannot create active elements while trusted citations and Markdown remain usable', async () => {
  const raw = '<iframe srcdoc="<script>parent.localStorage.clear()</script>"></iframe>\n'
    + '<img src=x onerror="alert(1)">\n**evidence** [1]';
  const safe = refs.escapeModelHtml(raw).replace('[1]', '<span class="source-file-link" data-file-id="source-file-0">[1]</span>');
  const rendered = new MarkdownIt({ html: true }).render(safe);
  assert.doesNotMatch(rendered, /<(?:iframe|script|img)\b/i);
  assert.match(rendered, /<strong>evidence<\/strong>/);
  assert.match(rendered, /<span class="source-file-link" data-file-id="source-file-0">\[1\]<\/span>/);
  const source = await readFile(new URL('../src/views/chat/modules/chat-message.vue', import.meta.url), 'utf8');
  assert.match(source, /processSourceLinks\(escapeModelHtml\(rawContent\)\)/);
});

test('chat and history disable Markdown attribute injection with the same renderer configuration', async () => {
  const vendor = await readFile(new URL('../src/vendor/vue-markdown-shiki.ts', import.meta.url), 'utf8');
  assert.match(vendor, /attrs:\s*\{\s*disable:\s*true\s*\}/);
  for (const path of ['../src/views/chat/modules/chat-list.vue', '../src/views/chat-history/index.vue']) {
    const source = await readFile(new URL(path, import.meta.url), 'utf8');
    assert.match(source, /<VueMarkdownItProvider :options="safeMarkdownOptions">/);
  }
  const main = await readFile(new URL('../src/main.ts', import.meta.url), 'utf8');
  assert.match(main, /app\.use\(markdownPlugin, safeMarkdownOptions\)/);
});

test('history and completion keep Figure metadata but drop internal paths and URLs', () => {
  const result = refs.sanitizeReferenceMappings({ 3: { ...figure, imagePath: 'figures/private.jpg', signedUrl: 'secret' },
    1: { fileMd5: figure.fileMd5, documentType: 'TEXT', anchorText: 'text', chunkId: 1 }, 0: figure, invalid: figure });
  assert.equal(result['3'].figureIndex, 2); assert.equal(result['3'].caption, 'caption');
  assert.equal(result['1'].anchorText, 'text'); assert.equal(result['3'].imagePath, undefined);
  assert.equal(result['3'].signedUrl, undefined); assert.deepEqual(Object.keys(result), ['1', '3']);
});
test('stable Figure requests use only business identity', () => {
  assert.equal(refs.figureImagePath({ ...figure, imagePath: 'merged/other' }), `documents/figures/${figure.fileMd5}/1/4/2/image`);
  for (const invalid of [{ processingGeneration: 0 }, { figureIndex: -1 }, { pageNumber: null }, { fileMd5: '../secret' }, { documentType: 'TEXT' }]) {
    assert.throws(() => refs.figureImagePath({ ...figure, ...invalid }));
  }
});
test('both citation styles retain stable appearance order and deduplicate', () => {
  assert.equal(JSON.stringify(refs.citedReferenceNumbers('证据[3] [1] (来源#3: paper) [2] [0]')), '[3,1,2]');
});
test('malformed history becomes empty mappings', () => {
  for (const value of [null, undefined, 'bad', { 1: null }, { 1: { caption: 'bad' } }]) assert.equal(JSON.stringify(refs.sanitizeReferenceMappings(value)), '{}');
});

const clientSource = (await readFile(new URL('../src/service/api/figure.ts', import.meta.url), 'utf8'))
  .replaceAll('import.meta.env', 'testEnv');
function client(status = 200, type = 'image/jpeg', body = 'image', authorization = 'Bearer test-only') {
  const requests = [], tokens = [], exported = {};
  vm.runInNewContext(ts.transpileModule(clientSource, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, {
    exports: exported, testEnv: { DEV: true, VITE_HTTP_PROXY: 'Y' }, Blob,
    fetch: async (url, options) => { requests.push({ url, options }); return { ok: status === 200, status,
      headers: new Headers({ 'New-Token': 'refreshed-test-token' }), blob: async () => new Blob([body], { type }) }; },
    require: name => {
      if (name.includes('modules/auth')) return { useAuthStore: () => ({ setToken: token => tokens.push(token) }) };
      if (name.includes('utils/service')) return { getServiceBaseURL: () => ({ baseURL: '/proxy-default/' }) };
      if (name.includes('utils/references')) return refs;
      if (name.includes('request/shared')) return { getAuthorization: () => authorization };
      throw new Error(name);
    }
  });
  return { read: exported.fetchFigureImage, requests, tokens };
}
test('image fetch is authenticated, uncached and supports token refresh', async () => {
  const c = client(); await c.read(figure);
  assert.equal(c.requests[0].options.headers.Authorization, 'Bearer test-only');
  assert.equal(c.requests[0].options.cache, 'no-store'); assert.equal(c.tokens[0], 'refreshed-test-token');
  assert.equal(c.requests[0].url, `/proxy-default/documents/figures/${figure.fileMd5}/1/4/2/image`);
});
for (const type of ['image/jpeg', 'image/png', 'image/webp']) test(`supports ${type}`, async () => { assert.equal((await client(200, type).read(figure)).type, type); });
for (const status of [401, 403, 404, 409, 502]) test(`HTTP ${status} fails explicitly`, async () => { await assert.rejects(client(status).read(figure)); });
test('no login never sends image request', async () => { const c = client(200, 'image/jpeg', 'image', ''); await assert.rejects(c.read(figure)); assert.equal(c.requests.length, 0); });
test('HTML, empty and oversized responses are rejected', async () => {
  await assert.rejects(client(200, 'text/html').read(figure)); await assert.rejects(client(200, 'image/png', '').read(figure));
  await assert.rejects(client(200, 'image/png', new Uint8Array(10 * 1024 * 1024 + 1)).read(figure));
});
test('Figure component revalidates enlargement, aborts and releases object URLs on unmount', async () => {
  const source = await readFile(new URL('../src/components/custom/figure-reference.vue', import.meta.url), 'utf8');
  assert.match(source, /async function enlarge\(\)[\s\S]*?await loadImage\(\)/);
  assert.match(source, /onBeforeUnmount\([\s\S]*?controller\?\.abort\(\)[\s\S]*?releaseImage\(\)/);
  assert.match(source, /URL\.revokeObjectURL/); assert.doesNotMatch(source, /imagePath/);
});

async function figureComponent() {
  const component = await readFile(new URL('../src/components/custom/figure-reference.vue', import.meta.url), 'utf8');
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
  const exported = {}, requests = [], revoked = [], listeners = new Map();
  let dispose, failure;
  const compiled = ts.transpileModule(`${script}\nexports.probe = { loadImage, enlarge, imageUrl, enlarged, error };`,
    { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  vm.runInNewContext(compiled, {
    exports: exported, defineProps: () => ({ reference: figure }), defineEmits: () => () => {}, AbortController, Error,
    URL: { createObjectURL: () => `blob:test-${requests.length}`, revokeObjectURL: url => revoked.push(url) },
    document: { visibilityState: 'visible' },
    window: { addEventListener: (name, handler) => listeners.set(name, handler), removeEventListener: name => listeners.delete(name) },
    require: name => {
      if (name === 'vue') return { ref: value => ({ value }), watch: (_source, callback) => callback(), onBeforeUnmount: callback => { dispose = callback; } };
      if (name.includes('api/figure')) return { fetchFigureImage: async (_reference, signal) => {
        requests.push(signal); if (failure) throw failure; return new Blob(['image'], { type: 'image/jpeg' });
      } };
      throw new Error(name);
    }
  });
  await setImmediate();
  return { ...exported.probe, requests, revoked, listeners, dispose: () => dispose(), fail: () => { failure = new Error('当前已无权查看该 Figure'); } };
}
test('Figure enlargement performs a fresh authorized read and unmount releases all resources', async () => {
  const c = await figureComponent(); assert.equal(c.imageUrl.value, 'blob:test-1');
  await c.enlarge(); assert.equal(c.requests.length, 2); assert.equal(c.enlarged.value, true);
  assert.deepEqual(c.revoked, ['blob:test-1']); c.dispose();
  assert.equal(c.requests.at(-1).aborted, true); assert.equal(c.imageUrl.value, ''); assert.equal(c.listeners.size, 0);
  assert.deepEqual(c.revoked, ['blob:test-1', 'blob:test-2']);
});
test('revoked historical Figure clears the previous thumbnail and never opens a large image', async () => {
  const c = await figureComponent(); c.fail(); await c.enlarge();
  assert.equal(c.imageUrl.value, ''); assert.equal(c.enlarged.value, false);
  assert.equal(c.error.value, '当前已无权查看该 Figure'); assert.deepEqual(c.revoked, ['blob:test-1']); c.dispose();
});
