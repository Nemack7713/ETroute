module.exports = {
  title: 'ETroute Engineering',
  tagline: 'Contracts, evidence, and planned native runtime admission',
  url: 'https://nemack7713.github.io', baseUrl: '/ETroute/', trailingSlash: true,
  onBrokenLinks: 'throw',
  markdown: {mermaid: true, hooks: {onBrokenMarkdownLinks: 'throw'}},
  themes: ['@docusaurus/theme-mermaid'],
  presets: [['classic', {
    docs: {routeBasePath: '/', sidebarPath: './sidebars.js',
      editUrl: 'https://github.com/Nemack7713/ETroute/edit/docs/runtimepack-admission/docs-site/'},
    blog: false, theme: {customCss: './src/css/custom.css'},
  }]],
  themeConfig: {
    navbar: {title: 'ETroute', items: [
      {type: 'docSidebar', sidebarId: 'engineering', label: 'Enhancement roadmap', position: 'left'},
      {href: 'https://github.com/Nemack7713/ETroute', label: 'Source', position: 'right'}]},
    colorMode: {respectPrefersColorScheme: true},
  },
};
