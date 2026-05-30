package io.kairo.assistant.tool.browser;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import io.kairo.api.tool.JsonSchema;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.Tool;
import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolContext;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

@Tool(
        name = "browser_automation",
        description =
                "Control a headless browser: navigate to URLs, click elements, type text, "
                        + "take screenshots, extract page content, execute JavaScript, scroll, "
                        + "manage tabs, and wait for conditions. "
                        + "Actions: navigate, click, type, screenshot, extract_text, get_html, "
                        + "execute_js, scroll, wait, tabs, get_cookies, set_cookie, close.",
        category = ToolCategory.INFORMATION,
        sideEffect = ToolSideEffect.WRITE)
public class PlaywrightBrowserTool implements SyncTool {

    private static final BrowserSessionManager SHARED_MANAGER = new BrowserSessionManager(
            !"false".equalsIgnoreCase(System.getenv("KAIRO_BROWSER_HEADLESS")));

    public static BrowserSessionManager sharedManager() {
        return SHARED_MANAGER;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, JsonSchema> props = new LinkedHashMap<>();
        props.put("action", new JsonSchema("string", null, null,
                "Action to perform: navigate, click, type, screenshot, extract_text, "
                        + "get_html, execute_js, scroll, wait, tabs, get_cookies, set_cookie, close"));
        props.put("url", new JsonSchema("string", null, null,
                "URL to navigate to (for 'navigate' action)"));
        props.put("selector", new JsonSchema("string", null, null,
                "CSS selector for element interactions (click, type, scroll, extract_text)"));
        props.put("text", new JsonSchema("string", null, null,
                "Text to type (for 'type' action) or JS code (for 'execute_js')"));
        props.put("direction", new JsonSchema("string", null, null,
                "Scroll direction: 'up' or 'down' (default: down)"));
        props.put("amount", new JsonSchema("integer", null, null,
                "Scroll amount in pixels (default: 500)"));
        props.put("timeout", new JsonSchema("integer", null, null,
                "Wait timeout in milliseconds (default: 5000)"));
        props.put("wait_for", new JsonSchema("string", null, null,
                "CSS selector or text to wait for (for 'wait' action)"));
        props.put("tab_action", new JsonSchema("string", null, null,
                "Tab sub-action: 'list', 'new', 'switch', 'close'"));
        props.put("tab_index", new JsonSchema("integer", null, null,
                "Tab index for switch/close"));
        props.put("cookie_name", new JsonSchema("string", null, null,
                "Cookie name (for set_cookie)"));
        props.put("cookie_value", new JsonSchema("string", null, null,
                "Cookie value (for set_cookie)"));
        props.put("cookie_domain", new JsonSchema("string", null, null,
                "Cookie domain (for set_cookie)"));
        return new JsonSchema("object", props, List.of("action"), null);
    }

    @Override
    public Mono<ToolResult> execute(Map<String, Object> args, ToolContext ctx) {
        return Mono.fromCallable(() -> doExecute(args, ctx));
    }

    private ToolResult doExecute(Map<String, Object> args, ToolContext ctx) {
        if ("false".equalsIgnoreCase(System.getenv("KAIRO_BROWSER_ENABLED"))) {
            return ToolResult.error("browser_automation",
                    "Browser automation is disabled (KAIRO_BROWSER_ENABLED=false)");
        }

        String action = (String) args.get("action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("browser_automation", "'action' required");
        }

        String sessionId = ctx != null && ctx.sessionId() != null
                ? ctx.sessionId() : "default";

        ToolResult validationError = validateArgs(action, args);
        if (validationError != null) return validationError;

        if ("close".equals(action)) {
            SHARED_MANAGER.close(sessionId);
            return ToolResult.success("browser_automation", "Browser session closed");
        }

        BrowserSessionManager.BrowserSession session = SHARED_MANAGER.getOrCreate(sessionId);
        Page page = session.activePage();

        try {
            return switch (action) {
                case "navigate" -> navigate(page, args);
                case "click" -> click(page, args);
                case "type" -> type(page, args);
                case "screenshot" -> screenshot(page, args);
                case "extract_text" -> extractText(page, args);
                case "get_html" -> getHtml(page, args);
                case "execute_js" -> executeJs(page, args);
                case "scroll" -> scroll(page, args);
                case "wait" -> waitFor(page, args);
                case "tabs" -> manageTabs(session, args);
                case "get_cookies" -> getCookies(session);
                case "set_cookie" -> setCookie(session, args);
                default -> ToolResult.error("browser_automation",
                        "Unknown action: " + action);
            };
        } catch (Exception e) {
            return ToolResult.error("browser_automation",
                    action + " failed: " + e.getMessage());
        }
    }

    private ToolResult validateArgs(String action, Map<String, Object> args) {
        return switch (action) {
            case "navigate" -> {
                String url = (String) args.get("url");
                yield (url == null || url.isBlank())
                        ? ToolResult.error("browser_automation", "'url' required for navigate")
                        : null;
            }
            case "click" -> {
                String sel = (String) args.get("selector");
                yield (sel == null || sel.isBlank())
                        ? ToolResult.error("browser_automation", "'selector' required for click")
                        : null;
            }
            case "type" -> {
                String sel = (String) args.get("selector");
                String text = (String) args.get("text");
                if (sel == null || sel.isBlank())
                    yield ToolResult.error("browser_automation", "'selector' required for type");
                if (text == null)
                    yield ToolResult.error("browser_automation", "'text' required for type");
                yield null;
            }
            case "execute_js" -> {
                String code = (String) args.get("text");
                yield (code == null || code.isBlank())
                        ? ToolResult.error("browser_automation",
                        "'text' required for execute_js (contains JS code)")
                        : null;
            }
            case "set_cookie" -> {
                String name = (String) args.get("cookie_name");
                String value = (String) args.get("cookie_value");
                yield (name == null || value == null)
                        ? ToolResult.error("browser_automation",
                        "'cookie_name' and 'cookie_value' required")
                        : null;
            }
            case "screenshot", "extract_text", "get_html", "scroll", "wait",
                    "tabs", "get_cookies", "close" -> null;
            default -> ToolResult.error("browser_automation", "Unknown action: " + action);
        };
    }

    private ToolResult navigate(Page page, Map<String, Object> args) {
        String url = (String) args.get("url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        page.navigate(url);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        String title = page.title();
        return ToolResult.success("browser_automation",
                "Navigated to: " + page.url() + "\nTitle: " + title);
    }

    private ToolResult click(Page page, Map<String, Object> args) {
        String selector = (String) args.get("selector");
        page.click(selector);
        return ToolResult.success("browser_automation",
                "Clicked element: " + selector + "\nCurrent URL: " + page.url());
    }

    private ToolResult type(Page page, Map<String, Object> args) {
        String selector = (String) args.get("selector");
        String text = (String) args.get("text");
        page.fill(selector, text);
        return ToolResult.success("browser_automation",
                "Typed into '" + selector + "': " + text);
    }

    private ToolResult screenshot(Page page, Map<String, Object> args) {
        byte[] bytes = page.screenshot(new Page.ScreenshotOptions()
                .setType(ScreenshotType.PNG)
                .setFullPage(false));

        try {
            Path tmpFile = Files.createTempFile("browser-screenshot-", ".png");
            tmpFile.toFile().deleteOnExit();
            Files.write(tmpFile, bytes);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String sizeKb = String.format("%.1f", bytes.length / 1024.0);
            return ToolResult.success("browser_automation",
                    "Screenshot captured (" + sizeKb + " KB)\n"
                            + "Saved to: " + tmpFile.toAbsolutePath() + "\n"
                            + "URL: " + page.url() + "\n"
                            + "Base64 length: " + base64.length() + " chars");
        } catch (Exception e) {
            return ToolResult.error("browser_automation",
                    "Screenshot saved but path creation failed: " + e.getMessage());
        }
    }

    private ToolResult extractText(Page page, Map<String, Object> args) {
        String selector = (String) args.get("selector");
        String text;
        if (selector != null && !selector.isBlank()) {
            ElementHandle element = page.querySelector(selector);
            if (element == null) {
                return ToolResult.error("browser_automation",
                        "Element not found: " + selector);
            }
            text = element.textContent();
        } else {
            text = page.textContent("body");
        }
        if (text != null && text.length() > 10000) {
            text = text.substring(0, 10000) + "\n... (truncated)";
        }
        return ToolResult.success("browser_automation",
                "Page text" + (selector != null ? " (" + selector + ")" : "") + ":\n" + text);
    }

    private ToolResult getHtml(Page page, Map<String, Object> args) {
        String selector = (String) args.get("selector");
        String html;
        if (selector != null && !selector.isBlank()) {
            ElementHandle element = page.querySelector(selector);
            if (element == null) {
                return ToolResult.error("browser_automation",
                        "Element not found: " + selector);
            }
            html = element.innerHTML();
        } else {
            html = page.content();
        }
        if (html.length() > 15000) {
            html = html.substring(0, 15000) + "\n... (truncated)";
        }
        return ToolResult.success("browser_automation",
                "HTML" + (selector != null ? " (" + selector + ")" : "") + ":\n" + html);
    }

    private ToolResult executeJs(Page page, Map<String, Object> args) {
        String code = (String) args.get("text");
        Object result = page.evaluate(code);
        String resultStr = result != null ? result.toString() : "undefined";
        if (resultStr.length() > 5000) {
            resultStr = resultStr.substring(0, 5000) + "... (truncated)";
        }
        return ToolResult.success("browser_automation", "JS result: " + resultStr);
    }

    private ToolResult scroll(Page page, Map<String, Object> args) {
        String direction = args.get("direction") instanceof String d ? d : "down";
        int amount = args.get("amount") instanceof Number n ? n.intValue() : 500;
        int scrollY = "up".equalsIgnoreCase(direction) ? -amount : amount;

        String selector = (String) args.get("selector");
        if (selector != null && !selector.isBlank()) {
            page.evaluate("(args) => { document.querySelector(args.sel).scrollBy(0, args.y); }",
                    Map.of("sel", selector, "y", scrollY));
        } else {
            page.evaluate("(y) => window.scrollBy(0, y)", scrollY);
        }
        return ToolResult.success("browser_automation",
                "Scrolled " + direction + " " + amount + "px");
    }

    private ToolResult waitFor(Page page, Map<String, Object> args) {
        String waitFor = (String) args.get("wait_for");
        int timeout = args.get("timeout") instanceof Number n ? n.intValue() : 5000;

        if (waitFor == null || waitFor.isBlank()) {
            page.waitForTimeout(timeout);
            return ToolResult.success("browser_automation",
                    "Waited " + timeout + "ms");
        }

        try {
            page.waitForSelector(waitFor,
                    new Page.WaitForSelectorOptions().setTimeout(timeout));
            return ToolResult.success("browser_automation",
                    "Element appeared: " + waitFor);
        } catch (Exception e) {
            return ToolResult.error("browser_automation",
                    "Timeout waiting for: " + waitFor);
        }
    }

    private ToolResult manageTabs(BrowserSessionManager.BrowserSession session,
                                  Map<String, Object> args) {
        String tabAction = args.get("tab_action") instanceof String s ? s : "list";
        var context = session.context();
        var pages = context.pages();

        return switch (tabAction) {
            case "list" -> {
                StringBuilder sb = new StringBuilder("Open tabs:\n");
                for (int i = 0; i < pages.size(); i++) {
                    Page p = pages.get(i);
                    String marker = p == session.activePage() ? " *" : "";
                    sb.append(i).append(": ").append(p.url())
                            .append(" - ").append(p.title()).append(marker).append("\n");
                }
                yield ToolResult.success("browser_automation", sb.toString());
            }
            case "new" -> {
                Page newPage = context.newPage();
                session.setActivePage(newPage);
                String url = (String) args.get("url");
                if (url != null && !url.isBlank()) {
                    newPage.navigate(url);
                }
                yield ToolResult.success("browser_automation",
                        "New tab opened" + (url != null ? ": " + url : ""));
            }
            case "switch" -> {
                int idx = args.get("tab_index") instanceof Number n ? n.intValue() : 0;
                if (idx < 0 || idx >= pages.size()) {
                    yield ToolResult.error("browser_automation",
                            "Invalid tab index: " + idx + " (total: " + pages.size() + ")");
                }
                session.setActivePage(pages.get(idx));
                yield ToolResult.success("browser_automation",
                        "Switched to tab " + idx + ": " + pages.get(idx).url());
            }
            case "close" -> {
                int idx = args.get("tab_index") instanceof Number n ? n.intValue() : -1;
                Page toClose = idx >= 0 && idx < pages.size()
                        ? pages.get(idx) : session.activePage();
                toClose.close();
                var remaining = context.pages();
                if (!remaining.isEmpty() && toClose == session.activePage()) {
                    session.setActivePage(remaining.get(remaining.size() - 1));
                }
                yield ToolResult.success("browser_automation", "Tab closed");
            }
            default -> ToolResult.error("browser_automation",
                    "Unknown tab_action: " + tabAction);
        };
    }

    private ToolResult getCookies(BrowserSessionManager.BrowserSession session) {
        var cookies = session.context().cookies();
        if (cookies.isEmpty()) {
            return ToolResult.success("browser_automation", "No cookies");
        }
        StringBuilder sb = new StringBuilder("Cookies:\n");
        for (var cookie : cookies) {
            sb.append("- ").append(cookie.name).append("=")
                    .append(cookie.value.length() > 50
                            ? cookie.value.substring(0, 50) + "..." : cookie.value)
                    .append(" (domain=").append(cookie.domain).append(")\n");
        }
        return ToolResult.success("browser_automation", sb.toString());
    }

    private ToolResult setCookie(BrowserSessionManager.BrowserSession session,
                                 Map<String, Object> args) {
        String name = (String) args.get("cookie_name");
        String value = (String) args.get("cookie_value");
        String domain = (String) args.get("cookie_domain");
        if (domain == null || domain.isBlank()) {
            String url = session.activePage().url();
            try {
                domain = new java.net.URI(url).getHost();
            } catch (Exception e) {
                domain = "localhost";
            }
        }
        session.context().addCookies(List.of(
                new com.microsoft.playwright.options.Cookie(name, value)
                        .setDomain(domain).setPath("/")));
        return ToolResult.success("browser_automation",
                "Cookie set: " + name + "=" + value + " (domain=" + domain + ")");
    }
}
