// Minimal i18n for the Kairo Console. Add a key to BOTH `en` and `zh` to
// expose a new string. The dictionary is intentionally flat — no
// pluralization or interpolation — to keep the import surface tiny.

export type Language = "en" | "zh";

export const translations = {
  en: {
    "app.title": "Kairo Console",
    "app.subtitle": "cron + evolution + observability",
    "app.backToAssistant": "💬 Chat with agent",

    "tab.tasks": "Tasks",
    "tab.create": "New Task",
    "tab.evolution": "Evolution",
    "tab.dashboard": "Dashboard",
    "tab.sessions": "Sessions",
    "tab.memory": "Memory",
    "tab.plugins": "Plugins",
    "tab.skills": "Skills",
    "tab.tools": "Tools",
    "tab.toolHistory": "Tool History",
    "tab.channels": "Channels",
    "tab.analytics": "Analytics",
    "tab.health": "Health",
    "tab.system": "System",
    "tab.systemPrompt": "Prompt",
    "tab.chat": "Chat",
    "tab.replay": "Replay",
    "tab.trace": "Trace",
    "tab.toolPlayground": "Playground",
    "tab.observability": "Observability",
    "tab.board": "Board",

    "nav.theme": "Theme",
    "nav.lang": "Language",
    "nav.help": "Help",

    "cat.run": "Run",
    "cat.history": "History",
    "cat.catalog": "Catalog",
    "cat.operate": "Operate",

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
    "app.backToAssistant": "💬 跟智能体聊天",

    "tab.tasks": "任务",
    "tab.create": "新建任务",
    "tab.evolution": "演化",
    "tab.dashboard": "概览",
    "tab.sessions": "会话",
    "tab.memory": "记忆",
    "tab.plugins": "插件",
    "tab.skills": "技能",
    "tab.tools": "工具",
    "tab.toolHistory": "工具历史",
    "tab.channels": "通道",
    "tab.analytics": "分析",
    "tab.health": "健康",
    "tab.system": "系统",
    "tab.systemPrompt": "提示词",
    "tab.chat": "聊天",
    "tab.replay": "重放",
    "tab.trace": "追踪",
    "tab.toolPlayground": "工具沙箱",
    "tab.observability": "可观测",
    "tab.board": "看板",

    "nav.theme": "主题",
    "nav.lang": "语言",
    "nav.help": "帮助",

    "cat.run": "运行",
    "cat.history": "历史",
    "cat.catalog": "目录",
    "cat.operate": "运维",

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
