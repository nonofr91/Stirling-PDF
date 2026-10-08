/** Writable destination types, matching the server's hosted filesystem restriction. */
export function availableOutputModes(
  hosted = false,
): ("folder" | "s3" | "vectordb" | "smtp")[] {
  return hosted
    ? ["s3", "vectordb", "smtp"]
    : ["folder", "s3", "vectordb", "smtp"];
}
