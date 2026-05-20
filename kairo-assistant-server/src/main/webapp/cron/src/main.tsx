import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { BrowserRouter } from "react-router-dom";
import { App } from "./App";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Cron tasks are slow-moving; refetch every 10s + on focus.
      refetchInterval: 10_000,
      refetchOnWindowFocus: true,
      retry: 1,
    },
  },
});

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter basename="/cron">
        <App />
        <Toaster
          richColors
          position="top-right"
          theme="dark"
          toastOptions={{ style: { fontSize: "13px" } }}
        />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
