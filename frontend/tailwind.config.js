/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: {
          DEFAULT: "#1b1a22",
          soft: "#54515f",
          faint: "#8a8794",
        },
        surface: {
          DEFAULT: "#ffffff",
          sunken: "#f6f4f1",
          raised: "#fbfaf8",
        },
        accent: {
          DEFAULT: "#5b46f2",
          soft: "#efecff",
          ring: "#c9c0ff",
        },
      },
      fontFamily: {
        sans: [
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "Segoe UI",
          "Roboto",
          "Helvetica Neue",
          "Arial",
          "sans-serif",
        ],
      },
      boxShadow: {
        card: "0 1px 2px rgba(27,26,34,0.04), 0 12px 32px -12px rgba(27,26,34,0.18)",
        pop: "0 8px 24px -8px rgba(27,26,34,0.22), 0 2px 6px rgba(27,26,34,0.06)",
      },
      borderRadius: {
        xl2: "1.25rem",
      },
      keyframes: {
        "fade-in": {
          from: { opacity: "0", transform: "translateY(4px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
        "slide-up": {
          from: { opacity: "0", transform: "translateY(8px)" },
          to: { opacity: "1", transform: "translateY(0)" },
        },
      },
      animation: {
        "fade-in": "fade-in 120ms ease-out",
        "slide-up": "slide-up 180ms cubic-bezier(0.16,1,0.3,1)",
      },
    },
  },
  plugins: [],
};
