# Figure 图片与引用

## 安全读取

`GET /api/v1/documents/figures/{fileMd5}/{generation}/{pageNumber}/{figureIndex}/image`

必须携带当前认证。Controller 只使用认证用户名；查询参数中的 imagePath、用户 ID 或 URL 都不能改变定位结果。

FigureAccessService 按以下顺序处理：

1. 校验 MD5、正整数 generation/page/index 和当前用户。
2. 从当前 MySQL 的 COMPLETED FileUpload 关系检查 owner / public / 有效组织授权，排除 PRIVATE_ 标签授权其他用户。
3. 校验 FileContent 未删除、generation 当前且 INDEXED。
4. 按文件 + generation + 页 + 页内索引查询唯一 DocumentFigure；只能读取其数据库 imagePath，且该路径必须符合对应稳定 Figure 身份。
5. 复用 FigureImageReader 从 MinIO SDK 读取，关闭流，默认上限 10 MiB。根据签名识别 JPEG、PNG、WebP MIME。这里只检查签名，不是完整图片解码校验。
6. 返回前再次检查当前数据库 ACL 和 generation；不将网络 I/O 包在长数据库事务中。

响应为图片 bytes，附正确 Content-Type、Content-Length、`Cache-Control: no-store`、`Pragma: no-cache`、`X-Content-Type-Options: nosniff`。不返回公开或预签名图片地址。

错误：未认证 401；非法身份 400；无当前授权 403；有权限但内容或 Figure 不存在 404；旧 generation 或未 INDEXED 409；MinIO 缺失、非法图片或损坏路径 502。无权限时先返回 403，避免探测他人文件的 Figure 信息。

## 前端引用

- 新 completion 与历史引用统一经过字段白名单，丢弃 imagePath。只使用业务身份请求图片。
- TEXT 保留证据、文件名、页码和原 PDF 定位。
- FIGURE 保留类型、稳定 ID、generation、页码、figureIndex/Label、caption、description、OCR 与 bbox。[N] 和旧来源格式均可点击。
- 回答来源仅展示实际被回答引用且映射存在的编号。Figure 使用缩略图和可放大图片，并提供原 PDF 页码入口。
- 模型新回答优先使用 `[N]`，具体来源元数据交给前端。模型接收的旧助手历史会移除该轮引用标记，避免把历史编号复用于当前证据；数据库历史与旧引用保持原样。Prompt 要求每个引用由当前编号的证据支持，不做自动答案重写或复杂引用评判。
- 图片通过带 Authorization 的 fetch 加载为临时 Blob URL；卸载、替换或失败时释放，卸载时取消请求。放大与窗口重新获得焦点都重新鉴权，不信任此前加载的缩略图。
- 图像 bytes 一旦已被授权下载，无法从用户设备撤回；撤销阻止后续读取，前端主动重新读取时清除旧图。不增加公开缓存。

## 调试与边界

WebSocket 连接日志只记录用户/会话身份，不输出带 Token 的路径。dev 默认 Web / Security 日志调整到 INFO；业务日志和单独类的调试能力仍保留。启用完整 HTTP path DEBUG 会记录旧 WebSocket 协议路径中的 Token，应避免。

本轮不改检索算法、ACL 架构、Kafka、INDEXED 状态机或 MinIO 布局。旧无 generation 的历史 Figure 不猜测当前代次；TEXT 历史仍兼容。未建立新的图片下载 Controller 调试入口或长期外链。

前端应用 typecheck 排除独立旧 `playwright-kb-column.spec.ts`：它需要仓库尚未安装的 @playwright/test，保留原测试源码，不能将此应用类型检查结果描述成该旧浏览器套件已运行。
