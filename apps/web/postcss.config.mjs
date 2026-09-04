// Tailwind v4 ships its own PostCSS plugin and needs no other entry — no
// autoprefixer, no tailwind.config.js. Turbopack resolves this from the Next
// project root, which is this directory.
const config = {
  plugins: {
    "@tailwindcss/postcss": {},
  },
};

export default config;
