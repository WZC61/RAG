<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { fetchFigureImage } from '@/service/api/figure';

const props = defineProps<{
  reference: Api.Chat.ReferenceEvidence;
  referenceNumber?: number;
}>();
const emit = defineEmits<{ openPdf: [] }>();
const imageUrl = ref('');
const loading = ref(false);
const error = ref('');
const enlarged = ref(false);
let controller: AbortController | null = null;
let requestId = 0;

function releaseImage() {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
  imageUrl.value = '';
}

async function loadImage() {
  requestId += 1;
  const id = requestId;
  controller?.abort();
  controller = new AbortController();
  releaseImage();
  loading.value = true;
  error.value = '';
  try {
    const blob = await fetchFigureImage(props.reference, controller.signal);
    if (id === requestId) imageUrl.value = URL.createObjectURL(blob);
  } catch (failure) {
    if (id === requestId && !controller.signal.aborted) {
      error.value = failure instanceof Error ? failure.message : '图片加载失败';
      enlarged.value = false;
    }
  } finally {
    if (id === requestId) loading.value = false;
  }
}

async function enlarge() {
  // A new authorized request instead of trusting the earlier thumbnail after revocation.
  await loadImage();
  enlarged.value = Boolean(imageUrl.value);
}

function revalidate() {
  if (document.visibilityState === 'visible') loadImage();
}
window.addEventListener('focus', revalidate);
watch(
  () => [
    props.reference.fileMd5,
    props.reference.processingGeneration,
    props.reference.pageNumber,
    props.reference.figureIndex
  ],
  () => {
    loadImage();
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  requestId += 1;
  controller?.abort();
  releaseImage();
  window.removeEventListener('focus', revalidate);
});
</script>

<template>
  <div
    class="figure-reference border border-gray-200 rounded-8px border-solid p-3"
    :data-reference-number="referenceNumber"
  >
    <div class="mb-2 flex flex-wrap items-center gap-2 text-sm">
      <span v-if="referenceNumber" class="font-bold">[{{ referenceNumber }}]</span>
      <NTag size="small" type="info">FIGURE</NTag>
      <span>{{ reference.fileName }}</span>
      <span v-if="reference.pageNumber">第{{ reference.pageNumber }}页</span>
      <span>{{ reference.figureLabel || `图 ${reference.figureIndex}` }}</span>
      <NButton text type="primary" @click="emit('openPdf')">查看原 PDF</NButton>
    </div>
    <NSpin :show="loading">
      <button
        v-if="imageUrl"
        type="button"
        class="block cursor-zoom-in border-0 bg-transparent p-0"
        aria-label="查看大图"
        @click="enlarge"
      >
        <img
          :src="imageUrl"
          :alt="reference.caption || reference.figureLabel || 'Figure'"
          class="max-h-180px max-w-full object-contain"
        />
      </button>
      <div v-else class="min-h-40px text-sm text-gray-500">
        {{ error || '正在加载图片…' }}
      </div>
    </NSpin>
    <p v-if="reference.caption" class="mt-2 whitespace-pre-wrap text-sm">
      {{ reference.caption }}
    </p>
    <p v-if="reference.description" class="mt-1 whitespace-pre-wrap text-sm text-gray-500">
      {{ reference.description }}
    </p>
    <NButton v-if="error" size="small" class="mt-2" @click="loadImage">重新读取</NButton>
    <NModal
      v-model:show="enlarged"
      preset="card"
      :title="reference.figureLabel || 'Figure'"
      class="max-w-90vw w-1000px"
    >
      <img
        v-if="imageUrl"
        :src="imageUrl"
        :alt="reference.caption || 'Figure'"
        class="max-h-75vh max-w-full object-contain"
      />
      <p v-if="reference.caption" class="mt-2">{{ reference.caption }}</p>
    </NModal>
  </div>
</template>
