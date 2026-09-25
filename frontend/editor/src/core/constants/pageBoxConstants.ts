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
