import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers/promises';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

// Execute the actual store with auto-imported dependencies replaced by a small test harness.
const source = await readFile(new URL('../src/store/modules/knowledge-base/index.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const UploadStatus = { Pending: 0, Uploading: 1, Completed: 2, Break: 3 };

function createStore(initResponse, options = {}) {
  const requests = [];
  const exports = {};
  const statusResponse =
    options.statusResponse ||
    (async () => ({
      error: null,
      data: { uploadedChunks: options.uploadedChunks || [], totalChunks: 2 }
    }));
  const chunkResponse =
    options.chunkResponse || (async request => ({ error: null, data: { chunkIndex: request.data.chunkIndex } }));
  const context = {
    exports,
    require: name => {
      if (name.includes('axios')) return { REQUEST_ID_KEY: 'X-Request-ID' };
      if (name.includes('utils')) return { nanoid: () => 'request-id' };
      throw new Error(`Unexpected import: ${name}`);
    },
    ref: value => ({ value }),
    defineStore: (_id, setup) => setup,
    SetupStoreId: { KnowledgeBase: 'knowledge-base' },
    UploadStatus,
    chunkSize: 5,
    calculateMD5: async blob => {
      if (Object.hasOwn(blob, 'name')) return 'file-md5';
      return createHash('md5')
        .update(Buffer.from(await blob.arrayBuffer()))
        .digest('hex');
    },
    window: { $message: { success() {}, error() {} } },
    console: { error() {} },
    request: async requestOptions => {
      requests.push(requestOptions);
      if (requestOptions.url === '/upload/init') return await initResponse();
      if (requestOptions.url === '/upload/status') return await statusResponse();
      if (requestOptions.url === '/upload/chunk') return await chunkResponse(requestOptions);
      if (requestOptions.url === '/upload/merge') return { error: null, data: {} };
      throw new Error(`Unexpected URL: ${requestOptions.url}`);
    }
  };
  vm.runInNewContext(compiled, context);
  return { store: exports.useKnowledgeBaseStore(), requests };
}

function form() {
  return {
    fileList: [
      {
        file: {
          name: 'test.pdf',
          size: 10,
          slice: (start, end) => new Blob([Buffer.from('abcdefghij').subarray(start, end)], { type: 'application/pdf' })
        }
      }
    ],
    orgTag: 'TEAM_A',
    orgTagName: 'Team A',
    isPublic: false
  };
}

function decision(instantUpload) {
  return {
    error: null,
    data: {
      instantUpload,
      needsUpload: !instantUpload,
      id: 10,
      status: instantUpload ? 1 : 0,
      mergedAt: instantUpload ? '2026-10-01T12:00:00' : null,
      vectorizationStatus: null,
      vectorizationErrorMessage: null
    }
  };
}

async function waitForUpload(store, attempt = 0) {
  if (store.activeUploads.value.size === 0) return;
  if (attempt >= 100) assert.fail('Upload did not finish');
  await setImmediate();
  await waitForUpload(store, attempt + 1);
}

test('existing merged file finishes after init with no chunks or merge', async () => {
  const { store, requests } = createStore(async () => decision(true));
  await store.enqueueUpload(form());
  await waitForUpload(store);

  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init']
  );
  assert.equal(store.tasks.value[0].status, UploadStatus.Completed);
  assert.equal(store.tasks.value[0].progress, 100);
  assert.equal(store.tasks.value[0].vectorizationStatus, null);
  assert.equal(requests[0].data.fileMd5, 'file-md5');
  assert.equal(requests[0].data.totalChunks, 2);
  assert.equal(Object.hasOwn(requests[0].data, 'userId'), false);
});

test('missing merged file uploads chunks and merges only after init', async () => {
  const { store, requests } = createStore(async () => decision(false));
  await store.enqueueUpload(form());
  await waitForUpload(store);

  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init', '/upload/status', '/upload/chunk', '/upload/chunk', '/upload/merge']
  );
  assert.deepEqual(
    requests.filter(item => item.url === '/upload/chunk').map(item => item.data.chunkIndex),
    [0, 1]
  );
  assert.equal(store.tasks.value[0].status, UploadStatus.Completed);
  for (const request of requests.filter(item => item.url === '/upload/chunk')) {
    const content = request.data.chunkIndex === 0 ? 'abcde' : 'fghij';
    assert.equal(request.data.chunkMd5, createHash('md5').update(content).digest('hex'));
  }
});

test('no chunks are sent while init decision is pending', async () => {
  let resolveInit;
  const pending = new Promise(resolve => {
    resolveInit = resolve;
  });
  const { store, requests } = createStore(() => pending);
  await store.enqueueUpload(form());
  await setImmediate();
  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init']
  );

  resolveInit(decision(true));
  await waitForUpload(store);
  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init']
  );
});

test('init failure stops upload without falling through to chunks', async () => {
  const { store, requests } = createStore(async () => ({ error: new Error('storage unavailable'), data: null }));
  await store.enqueueUpload(form());
  await waitForUpload(store);

  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init']
  );
  assert.equal(store.tasks.value[0].status, UploadStatus.Break);
});

test('selecting a completed file again rechecks init without duplicating the local task', async () => {
  const { store, requests } = createStore(async () => decision(true));
  await store.enqueueUpload(form());
  await waitForUpload(store);
  await store.enqueueUpload(form());
  await waitForUpload(store);

  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init', '/upload/init']
  );
  assert.equal(store.tasks.value.length, 1);
  assert.equal(store.tasks.value[0].status, UploadStatus.Completed);
});

test('resume uploads only indexes missing from database status', async () => {
  const { store, requests } = createStore(async () => decision(false), { uploadedChunks: [0] });
  await store.enqueueUpload(form());
  await waitForUpload(store);

  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init', '/upload/status', '/upload/chunk', '/upload/merge']
  );
  assert.equal(requests.find(item => item.url === '/upload/chunk').data.chunkIndex, 1);
  assert.equal(store.tasks.value[0].status, UploadStatus.Completed);
});

test('all chunks confirmed by status go directly to merge', async () => {
  const { store, requests } = createStore(async () => decision(false), { uploadedChunks: [0, 1] });
  await store.enqueueUpload(form());
  await waitForUpload(store);
  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init', '/upload/status', '/upload/merge']
  );
});

test('ordinary progress counts successful requests while other chunks are pending', async () => {
  let finishSecond;
  let secondStarted;
  const secondPending = new Promise(resolve => {
    finishSecond = resolve;
  });
  const secondReady = new Promise(resolve => {
    secondStarted = resolve;
  });
  const { store } = createStore(async () => decision(false), {
    chunkResponse: request => {
      if (request.data.chunkIndex === 0) return { error: null, data: { uploaded: [0, 1], progress: 100 } };
      secondStarted();
      return secondPending;
    }
  });
  await store.enqueueUpload(form());
  await secondReady;
  await setImmediate();

  assert.equal(store.tasks.value[0].progress, 50);
  assert.deepEqual(Array.from(store.tasks.value[0].uploadedChunks), [0]);
  finishSecond({ error: null, data: { chunkIndex: 1 } });
  await waitForUpload(store);
  assert.equal(store.tasks.value[0].progress, 100);
});

test('failed chunks are not counted as successful and never trigger merge', async () => {
  const { store, requests } = createStore(async () => decision(false), {
    chunkResponse: request =>
      request.data.chunkIndex === 0
        ? { error: null, data: { chunkIndex: 0 } }
        : { error: new Error('failed'), data: null }
  });
  await store.enqueueUpload(form());
  await waitForUpload(store);
  assert.equal(store.tasks.value[0].status, UploadStatus.Break);
  assert.equal(store.tasks.value[0].progress, 50);
  assert.deepEqual(Array.from(store.tasks.value[0].uploadedChunks), [0]);
  assert.equal(
    requests.some(item => item.url === '/upload/merge'),
    false
  );
});

test('status failure stops before uploading any chunk', async () => {
  const { store, requests } = createStore(async () => decision(false), {
    statusResponse: async () => ({ error: new Error('database unavailable'), data: null })
  });
  await store.enqueueUpload(form());
  await waitForUpload(store);
  assert.deepEqual(
    requests.map(item => item.url),
    ['/upload/init', '/upload/status']
  );
  assert.equal(store.tasks.value[0].status, UploadStatus.Break);
});
