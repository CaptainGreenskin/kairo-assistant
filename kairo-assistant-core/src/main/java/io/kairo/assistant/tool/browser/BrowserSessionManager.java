package io.kairo.assistant.tool.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrowserSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionManager.class);
    private static final long IDLE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long SWEEP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(2);

    private final boolean headless;
    private final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock initLock = new ReentrantLock();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "browser-session-sweeper");
        t.setDaemon(true);
        return t;
    });
    private volatile Playwright playwright;
    private volatile Browser browser;

    public BrowserSessionManager(boolean headless) {
        this.headless = headless;
        sweeper.scheduleAtFixedRate(
                this::evictIdle, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public BrowserSession getOrCreate(String sessionId) {
        BrowserSession session = sessions.computeIfAbsent(sessionId, id -> {
            ensureBrowserStarted();
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            log.info("Created browser session [{}]", id);
            return new BrowserSession(context, page);
        });
        session.touch();
        return session;
    }

    public BrowserSession get(String sessionId) {
        BrowserSession session = sessions.get(sessionId);
        if (session != null) session.touch();
        return session;
    }

    /** Closes sessions untouched for longer than {@link #IDLE_TTL_MS} to bound Chromium contexts. */
    private void evictIdle() {
        long cutoff = System.currentTimeMillis() - IDLE_TTL_MS;
        for (var entry : sessions.entrySet()) {
            if (entry.getValue().lastAccessedMillis() < cutoff) {
                log.info("Evicting idle browser session [{}]", entry.getKey());
                close(entry.getKey());
            }
        }
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
        sweeper.shutdownNow();
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
        private volatile long lastAccessedMillis = System.currentTimeMillis();

        BrowserSession(BrowserContext context, Page page) {
            this.context = context;
            this.activePage = page;
        }

        public BrowserContext context() { return context; }

        public Page activePage() { return activePage; }

        public void setActivePage(Page page) { this.activePage = page; }

        void touch() { this.lastAccessedMillis = System.currentTimeMillis(); }

        long lastAccessedMillis() { return lastAccessedMillis; }
    }
}
