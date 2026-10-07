import markdownPlugin, { VueMarkdownIt, VueMarkdownItProvider } from 'vue-markdown-shiki/dist/index.mjs';

// Keep trusted citation HTML, but do not let model Markdown attach arbitrary DOM attributes.
export const safeMarkdownOptions = {
  theme: 'dracula-soft',
  defaultHighlightLang: 'javascript',
  attrs: { disable: true }
};

export { VueMarkdownIt, VueMarkdownItProvider };
export default markdownPlugin;
