package stirling.software.proprietary.policy.network;

/**
 * One directory listing entry for interactive browsing: a file or a subdirectory. Unlike {@link
 * RemoteFile} (pipeline listing, files only), browsing needs directories so the user can descend
 * into them.
 */
public record RemoteEntry(
        String path, String name, boolean directory, long size, long lastModifiedMs) {}
