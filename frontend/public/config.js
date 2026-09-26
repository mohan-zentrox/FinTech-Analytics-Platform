// Runtime configuration, overriding the values Vite baked in at build time.
//
// The Docker image rewrites this file from environment variables at container
// start (see docker-entrypoint.sh), which is what lets one prebuilt image be
// pointed at any backend. When the app is built by the host (Vercel, Netlify,
// Cloudflare Pages) the VITE_* build-time values are used instead and this file
// can stay as-is.
window.__LEDGER_CONFIG__ = {
  coreApiBaseUrl: "",
  analyticsApiBaseUrl: "",
};
