// PDF page boxes defined by ISO 32000, ordered from largest to smallest
// extent. MEDIA_BOX must stay first: it is the mandatory box every page has.
export const PAGE_BOXES = [
  "MEDIA_BOX",
  "CROP_BOX",
  "TRIM_BOX",
  "BLEED_BOX",
  "ART_BOX",
] as const;

export type PageBox = (typeof PAGE_BOXES)[number];

/** Shared by the settings diagram and the viewer overlay so a box keeps the
 *  same colour in both places. */
export const PAGE_BOX_COLORS: Record<PageBox, string> = {
  MEDIA_BOX: "var(--mantine-color-gray-6)",
  CROP_BOX: "var(--mantine-color-blue-6)",
  TRIM_BOX: "var(--mantine-color-green-6)",
  BLEED_BOX: "var(--mantine-color-red-6)",
  ART_BOX: "var(--mantine-color-violet-6)",
};
