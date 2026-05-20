// Minimal i18n for the Kairo Console. Add a key to BOTH `en` and `zh` to
// expose a new string. The dictionary is intentionally flat — no
// pluralization or interpolation — to keep the import surface tiny.

export type Language = "en" | "zh";

export const translations = {
  en: {
    "app.title": "Kairo Console",
    "app.subtitle": "cron + evolution + observability",
    "app.backToAssistant": "← Back to assistant",

    "tab.tasks": "Tasks",
    "tab.create": "New Task",
    "tab.evolution": "Evolution",
    "tab.dashboard": "Dashboard",
    "tab.sessions": "Sessions",
    "tab.memory": "Memory",
    "tab.plugins": "Plugins",
    "tab.skills": "Skills",
    "tab.tools": "Tools",
    "tab.channels": "Channels",
    "tab.analytics": "Analytics",
    "tab.health": "Health",

    "nav.theme": "Theme",
    "nav.lang": "Language",
    "nav.help": "Help",

    "status.live": "live",
    "status.connecting": "connecting",
    "status.offline": "offline",
    "status.shortcuts": "Shortcuts",
    "status.tabs": "tabs",
    "status.theme": "theme",

    "help.title": "Keyboard shortcuts",
    "help.tabPrefix": "Switch to tab",
    "help.theme": "Cycle theme",
    "help.lang": "Toggle language",
    "help.help": "Show this help",
    "help.close": "Close (Esc)",
  },
  zh: {
    "app.title": "Kairo 控制台",
    "app.subtitle": "定时任务 + 演化 + 可观测",
    "app.backToAssistant": "← 返回助手",

    "tab.tasks": "任务",
    "tab.create": "新建任务",
    "tab.evolution": "演化",
    "tab.dashboard": "概览",
    "tab.sessions": "会话",
    "tab.memory": "记忆",
    "tab.plugins": "插件",
    "tab.skills": "技能",
    "tab.tools": "工具",
    "tab.channels": "通道",
    "tab.analytics": "分析",
    "tab.health": "健康",

    "nav.theme": "主题",
    "nav.lang": "语言",
    "nav.help": "帮助",

    "status.live": "实时",
    "status.connecting": "连接中",
    "status.offline": "离线",
    "status.shortcuts": "快捷键",
    "status.tabs": "标签",
    "status.theme": "主题",

    "help.title": "键盘快捷键",
    "help.tabPrefix": "切换到标签",
    "help.theme": "切换主题",
    "help.lang": "切换语言",
    "help.help": "显示此帮助",
    "help.close": "关闭 (Esc)",
  },
} as const satisfies Record<Language, Record<string, string>>;

export type TranslationKey = keyof (typeof translations)["en"];
