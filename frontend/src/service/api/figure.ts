import { useAuthStore } from '@/store/modules/auth';
import { getServiceBaseURL } from '@/utils/service';
import { figureImagePath } from '@/utils/references';
import { getAuthorization } from '../request/shared';

export async function fetchFigureImage(reference: Api.Chat.ReferenceEvidence, signal?: AbortSignal): Promise<Blob> {
  const authorization = getAuthorization();
  if (!authorization) throw new Error('请先登录后查看图片');
  const { baseURL } = getServiceBaseURL(
    import.meta.env,
    import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y'
  );
  const response = await fetch(`${baseURL.replace(/\/$/, '')}/${figureImagePath(reference)}`, {
    headers: { Authorization: authorization },
    cache: 'no-store',
    signal
  });
  const newToken = response.headers.get('New-Token');
  if (newToken) useAuthStore().setToken(newToken);
  if (!response.ok) {
    const messages: Record<number, string> = {
      401: '登录已失效，请重新登录',
      403: '当前已无权查看该 Figure',
      404: 'Figure 已删除或不存在',
      409: 'Figure 代次已失效，请重新获取当前来源',
      502: '图片暂时无法读取'
    };
    throw new Error(messages[response.status] || '图片读取失败');
  }
  const blob = await response.blob();
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(blob.type) || !blob.size || blob.size > 10 * 1024 * 1024) {
    throw new Error('图片响应格式或大小不合法');
  }
  return blob;
}
