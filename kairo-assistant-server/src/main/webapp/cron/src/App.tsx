import { Route, Routes } from "react-router-dom";
import { ConsoleShell } from "./components/ConsoleShell";
import { TABS } from "./lib/console/tabs";
import { EventStreamProvider } from "./hooks/useEventStream";

export function App() {
  return (
    <EventStreamProvider>
      <ConsoleShell>
        <Routes>
          {TABS.map((tab) => {
            const C = tab.component;
            return <Route key={tab.id} path={tab.path} element={<C />} />;
          })}
        </Routes>
      </ConsoleShell>
    </EventStreamProvider>
  );
}
