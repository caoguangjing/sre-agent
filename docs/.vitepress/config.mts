import {defineConfig} from 'vitepress';
import {readFileSync} from 'node:fs';

export default defineConfig({
  title: '智能运维平台',
  description: 'AgentScope 驱动的中文智能运维平台',
  lang: 'zh-CN',
  themeConfig: {
    nav: [
      {text: '运行指南', link: '/getting-started'},
      {text: '系统架构', link: '/architecture'},
      {text: '联调', link: '/tutorials/real-log-and-alert'},
      {text: '设计记录', link: '/changes/'}
    ],
    sidebar: [
      {text: '使用与开发', items: [
        {text: '概览', link: '/'},
        {text: '配置与启动', link: '/getting-started'},
        {text: 'macOS', link: '/setup/macos'},
        {text: 'Linux', link: '/setup/linux'},
        {text: 'Windows', link: '/setup/windows'},
        {text: '系统架构', link: '/architecture'},
        {text: '功能索引', link: '/coverage'},
        {text: '日志与告警联调', link: '/tutorials/real-log-and-alert'},
        {text: '运维与监控', link: '/operations-and-monitoring'}
      ]},
      ...JSON.parse(readFileSync(new URL('./wiki-sidebar.json', import.meta.url), 'utf8'))
    ]
  }
});
