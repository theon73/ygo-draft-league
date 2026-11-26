import type { BanStatus } from "../../api/client";

export const statusLabel: Record<BanStatus, string> = {
  FORBIDDEN: "Forbidden",
  LIMITED: "Limited",
  SEMI_LIMITED: "Semi-Limited",
};
