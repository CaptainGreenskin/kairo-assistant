/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Aligns with the legacy index.html dark theme palette.
        bg: "#1a1a2e",
        surface: "#16213e",
        primary: "#0f3460",
        accent: "#e94560",
        "accent-hover": "#d63850",
        text: "#eaeaea",
        "text-dim": "#a0a0a0",
        border: "#2a3a5c",
        success: "#4caf50",
        warn: "#ff9800",
      },
    },
  },
  plugins: [],
};
