import { Link, Route, Routes, useLocation } from "react-router-dom";
import { TaskListPage } from "./pages/TaskListPage";
import { TaskCreatePage } from "./pages/TaskCreatePage";

export function App() {
  const loc = useLocation();
  const tab = loc.pathname.startsWith("/create") ? "create" : "list";
  return (
    <div className="min-h-full flex flex-col">
      <header className="bg-surface border-b border-border px-6 py-3 flex items-center gap-6">
        <h1 className="text-base font-semibold tracking-tight">
          Kairo Cron <span className="text-text-dim font-normal">· scheduled tasks</span>
        </h1>
        <nav className="flex gap-1 text-sm">
          <Link
            to="/"
            className={tabClass(tab === "list")}
            data-active={tab === "list"}
          >
            Tasks
          </Link>
          <Link
            to="/create"
            className={tabClass(tab === "create")}
            data-active={tab === "create"}
          >
            New task
          </Link>
        </nav>
        <a
          href="/"
          className="ml-auto text-text-dim hover:text-text text-xs underline-offset-2 hover:underline"
        >
          ← Back to assistant
        </a>
      </header>
      <main className="flex-1 overflow-auto">
        <Routes>
          <Route path="/" element={<TaskListPage />} />
          <Route path="/create" element={<TaskCreatePage />} />
        </Routes>
      </main>
    </div>
  );
}

function tabClass(active: boolean) {
  return [
    "px-3 py-1.5 rounded-md transition-colors",
    active
      ? "bg-primary text-text"
      : "text-text-dim hover:text-text hover:bg-primary/40",
  ].join(" ");
}
