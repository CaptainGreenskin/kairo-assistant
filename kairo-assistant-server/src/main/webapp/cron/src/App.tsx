import { Route, Routes } from "react-router-dom";
import { ConsoleShell } from "./components/ConsoleShell";
import { TABS } from "./lib/console/tabs";
import { useEventStream } from "./hooks/useEventStream";

export function App() {
  // SSE → React-Query invalidation. One subscription, shared by every tab.
  useEventStream();
  return (
    <ConsoleShell>
      <Routes>
        {TABS.map((tab) => {
          const C = tab.component;
          return <Route key={tab.id} path={tab.path} element={<C />} />;
        })}
      </Routes>
    </ConsoleShell>
  );
}
