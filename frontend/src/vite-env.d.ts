/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CORE_API_BASE_URL: string;
  readonly VITE_ANALYTICS_API_BASE_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
