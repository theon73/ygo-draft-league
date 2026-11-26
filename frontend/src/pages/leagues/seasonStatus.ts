import type { SeasonStatus } from "../../api/client";

export const seasonStatusLabel: Record<SeasonStatus, string> = {
  SETUP: "Setting up",
  DRAFTING: "Drafting",
  ACTIVE: "In progress",
  COMPLETE: "Complete",
};
