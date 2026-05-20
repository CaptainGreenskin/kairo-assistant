package io.kairo.assistant.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);

    private final boolean headless;
    private final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock initLock = new ReentrantLock();
    private volatile Playwright playwright;
    private volatile Browser browser;

    public BrowserSessionManager(boolean headless) {
        this.headless = headless;
    }

    public BrowserSession getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> {
            ensureBrowserStarted();
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            log.info("Created browser session [{}]", id);
            return new BrowserSession(context, page);
        });
    }

    public BrowserSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void close(String sessionId) {
        BrowserSession session = sessions.remove(sessionId);
        if (session != null) {
            try {
                session.context().close();
            } catch (Exception e) {
                log.debug("Error closing browser session [{}]: {}", sessionId, e.getMessage());
            }
            log.info("Closed browser session [{}]", sessionId);
        }
    }

    public void shutdown() {
        sessions.forEach((id, session) -> {
            try {
                session.context().close();
            } catch (Exception e) {
                log.debug("Error closing session during shutdown: {}", e.getMessage());
            }
        });
        sessions.clear();
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                log.debug("Error closing browser: {}", e.getMessage());
            }
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception e) {
                log.debug("Error closing playwright: {}", e.getMessage());
            }
        }
        log.info("Browser session manager shut down");
    }

    private void ensureBrowserStarted() {
        if (browser != null) return;
        initLock.lock();
        try {
            if (browser != null) return;
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(headless));
            log.info("Playwright browser started (headless={})", headless);
        } finally {
            initLock.unlock();
        }
    }

    public static class BrowserSession {
        private final BrowserContext context;
        private Page activePage;

        BrowserSession(BrowserContext context, Page page) {
            this.context = context;
            this.activePage = page;
        }

        public BrowserContext context() { return context; }

        public Page activePage() { return activePage; }

        public void setActivePage(Page page) { this.activePage = page; }
    }
}
