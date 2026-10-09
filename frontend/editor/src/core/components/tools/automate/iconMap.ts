import type { IconName } from "@app/ui/Icon";

/** Icon palette for saved automations and watched folders. The KEYS are
 * persisted (e.g. "SettingsIcon"), so only the values may change. */
export const iconMap: Record<string, IconName> = {
  SettingsIcon: "settings",
  CompressIcon: "shrink",
  SwapHorizIcon: "arrow-left-right",
  CleaningServicesIcon: "brush-cleaning",
  CropIcon: "crop",
  TextFieldsIcon: "type",
  PictureAsPdfIcon: "file-text",
  EditIcon: "pencil",
  DeleteIcon: "trash",
  FolderIcon: "folder",
  CloudIcon: "cloud",
  StorageIcon: "server",
  SearchIcon: "search",
  DownloadIcon: "download",
  UploadIcon: "upload",
  PlayArrowIcon: "play",
  RotateLeftIcon: "rotate-ccw",
  RotateRightIcon: "rotate-cw",
  VisibilityIcon: "eye",
  ContentCutIcon: "scissors",
  ContentCopyIcon: "copy",
  WorkIcon: "briefcase",
  BuildIcon: "wrench",
  AutoAwesomeIcon: "sparkles",
  SmartToyIcon: "bot",
  CheckIcon: "check",
  SecurityIcon: "shield-check",
  StarIcon: "star",
};

export const iconOptions = [
  {
    value: "SettingsIcon",
    label: "Settings",
    labelKey: "automate.icons.settings",
  },
  {
    value: "CompressIcon",
    label: "Compress",
    labelKey: "automate.icons.compress",
  },
  {
    value: "SwapHorizIcon",
    label: "Convert",
    labelKey: "automate.icons.convert",
  },
  {
    value: "CleaningServicesIcon",
    label: "Clean",
    labelKey: "automate.icons.clean",
  },
  { value: "CropIcon", label: "Crop", labelKey: "automate.icons.crop" },
  { value: "TextFieldsIcon", label: "Text", labelKey: "automate.icons.text" },
  { value: "PictureAsPdfIcon", label: "PDF", labelKey: "automate.icons.pdf" },
  { value: "EditIcon", label: "Edit", labelKey: "automate.icons.edit" },
  {
    value: "DeleteIcon",
    label: "Delete",
    labelKey: "automate.icons.delete",
  },
  {
    value: "FolderIcon",
    label: "Folder",
    labelKey: "automate.icons.folder",
  },
  { value: "CloudIcon", label: "Cloud", labelKey: "automate.icons.cloud" },
  {
    value: "StorageIcon",
    label: "Storage",
    labelKey: "automate.icons.storage",
  },
  {
    value: "SearchIcon",
    label: "Search",
    labelKey: "automate.icons.search",
  },
  {
    value: "DownloadIcon",
    label: "Download",
    labelKey: "automate.icons.download",
  },
  {
    value: "UploadIcon",
    label: "Upload",
    labelKey: "automate.icons.upload",
  },
  { value: "PlayArrowIcon", label: "Play", labelKey: "automate.icons.play" },
  {
    value: "RotateLeftIcon",
    label: "Rotate Left",
    labelKey: "automate.icons.rotateLeft",
  },
  {
    value: "RotateRightIcon",
    label: "Rotate Right",
    labelKey: "automate.icons.rotateRight",
  },
  { value: "VisibilityIcon", label: "View", labelKey: "automate.icons.view" },
  { value: "ContentCutIcon", label: "Cut", labelKey: "automate.icons.cut" },
  {
    value: "ContentCopyIcon",
    label: "Copy",
    labelKey: "automate.icons.copy",
  },
  { value: "WorkIcon", label: "Work", labelKey: "automate.icons.work" },
  { value: "BuildIcon", label: "Build", labelKey: "automate.icons.build" },
  {
    value: "AutoAwesomeIcon",
    label: "Magic",
    labelKey: "automate.icons.magic",
  },
  {
    value: "SmartToyIcon",
    label: "Robot",
    labelKey: "automate.icons.robot",
  },
  { value: "CheckIcon", label: "Check", labelKey: "automate.icons.check" },
  {
    value: "SecurityIcon",
    label: "Security",
    labelKey: "automate.icons.security",
  },
  { value: "StarIcon", label: "Star", labelKey: "automate.icons.star" },
];

export type IconKey = keyof typeof iconMap;
