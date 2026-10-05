/**
 * Folder icon presets. Each is a single emoji that overlays the folder
 * thumbnail. Picked to cover the most common organisational uses of a
 * folder without resorting to a custom icon system.
 *
 * Kept small (≤24) so the picker is one glanceable row in the menu.
 */

export interface FolderIconOption {
  id: string;
  glyph: string;
  /** English fallback label. */
  label: string;
  labelKey: string;
}

export const FOLDER_ICONS: FolderIconOption[] = [
  {
    id: "none",
    glyph: "",
    label: "No icon",
    labelKey: "filesPage.folderIcons.none",
  },
  {
    id: "star",
    glyph: "★",
    label: "Star",
    labelKey: "filesPage.folderIcons.star",
  },
  {
    id: "heart",
    glyph: "♥",
    label: "Heart",
    labelKey: "filesPage.folderIcons.heart",
  },
  {
    id: "work",
    glyph: "💼",
    label: "Work",
    labelKey: "filesPage.folderIcons.work",
  },
  {
    id: "home",
    glyph: "🏠",
    label: "Home",
    labelKey: "filesPage.folderIcons.home",
  },
  {
    id: "tax",
    glyph: "💰",
    label: "Money",
    labelKey: "filesPage.folderIcons.money",
  },
  {
    id: "receipt",
    glyph: "🧾",
    label: "Receipt",
    labelKey: "filesPage.folderIcons.receipt",
  },
  {
    id: "contract",
    glyph: "📝",
    label: "Contract",
    labelKey: "filesPage.folderIcons.contract",
  },
  { id: "id", glyph: "🪪", label: "ID", labelKey: "filesPage.folderIcons.id" },
  {
    id: "house",
    glyph: "🏡",
    label: "House",
    labelKey: "filesPage.folderIcons.house",
  },
  {
    id: "travel",
    glyph: "✈️",
    label: "Travel",
    labelKey: "filesPage.folderIcons.travel",
  },
  {
    id: "photos",
    glyph: "🖼️",
    label: "Photos",
    labelKey: "filesPage.folderIcons.photos",
  },
  {
    id: "music",
    glyph: "🎵",
    label: "Music",
    labelKey: "filesPage.folderIcons.music",
  },
  {
    id: "code",
    glyph: "💻",
    label: "Code",
    labelKey: "filesPage.folderIcons.code",
  },
  {
    id: "health",
    glyph: "🏥",
    label: "Health",
    labelKey: "filesPage.folderIcons.health",
  },
  {
    id: "school",
    glyph: "🎓",
    label: "School",
    labelKey: "filesPage.folderIcons.school",
  },
  {
    id: "warning",
    glyph: "⚠️",
    label: "Warning",
    labelKey: "filesPage.folderIcons.warning",
  },
  {
    id: "archive",
    glyph: "📦",
    label: "Archive",
    labelKey: "filesPage.folderIcons.archive",
  },
];

export function findFolderIcon(
  id: string | undefined,
): FolderIconOption | null {
  if (!id) return null;
  return FOLDER_ICONS.find((i) => i.id === id) ?? null;
}
