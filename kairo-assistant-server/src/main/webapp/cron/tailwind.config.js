/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // All colors bind to CSS variables so themes can swap them at runtime.
        // The palette names stay the same as the original hard-coded version so
        // existing components (bg-surface, text-text-dim, border-border, etc.)
        // keep working without edits.
        bg: "var(--kc-bg)",
        surface: "var(--kc-surface)",
        primary: "var(--kc-primary)",
        accent: "var(--kc-accent)",
        "accent-hover": "var(--kc-accent-hover)",
        text: "var(--kc-text)",
        "text-dim": "var(--kc-text-dim)",
        border: "var(--kc-border)",
        success: "var(--kc-success)",
        warn: "var(--kc-warn)",
      },
    },
  },
  plugins: [],
};
