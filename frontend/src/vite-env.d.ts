/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Where card images are served from. Default "/api/card-images" (the backend); later an R2 domain. */
  readonly VITE_CARD_IMAGE_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
