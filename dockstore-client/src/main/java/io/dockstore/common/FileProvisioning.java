/*
 *    Copyright 2016 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.common;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.zafarkhaja.semver.UnexpectedCharacterException;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.dockstore.provision.ProvisionInterface;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.configuration2.SubnodeConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.vfs2.AllFileSelector;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.fortsoft.pf4j.PluginManager;
import ro.fortsoft.pf4j.PluginWrapper;

/**
 * The purpose of this class is to provide general functions to deal with workflow file provisioning.
 * Created by aduncan on 10/03/16.
 */
public class FileProvisioning {

    private static final int DEFAULT_RETRIES = 3;
    private static final String FILE_PROVISION_RETRIES = "file-provision-retries";
    private static final Logger LOG = LoggerFactory.getLogger(FileProvisioning.class);

    private List<ProvisionInterface> plugins = new ArrayList<>();

    private INIConfiguration config;

    // map from cwl emitted local file path to info object containing
    private List<ImmutablePair<String, FileInfo>> registeredFiles = new ArrayList<>();

    /**
     * Constructor
     */
    public FileProvisioning(String configFile) {
        this.config = Utilities.parseConfig(configFile);
        try {
            PluginManager pluginManager = FileProvisionUtil.getPluginManager(config);

            this.plugins = pluginManager.getExtensions(ProvisionInterface.class);

            List<PluginWrapper> pluginWrappers = pluginManager.getPlugins();
            for (PluginWrapper pluginWrapper : pluginWrappers) {
                SubnodeConfiguration section = config.getSection(pluginWrapper.getPluginId());
                Map<String, String> sectionConfig = new HashMap<>();
                Iterator<String> keys = section.getKeys();
                keys.forEachRemaining(key -> sectionConfig.put(key, section.getString(key)));
                // this is ugly, but we need to pass configuration into the plugins
                // TODO: speed this up using a map of plugins
                for (ProvisionInterface extension : plugins) {
                    String extensionName = extension.getClass().getName();
                    String pluginClass = pluginWrapper.getDescriptor().getPluginClass();
                    if (extensionName.startsWith(pluginClass)) {
                        extension.setConfiguration(sectionConfig);
                    }
                }
            }
        } catch (UnexpectedCharacterException e) {
            LOG.error("Could not load plugins: " + e.toString(), e);
            throw new RuntimeException(e);
        }
    }

    /*
     * Returns the next wait interval, in milliseconds, using an exponential
     * backoff algorithm.
    */
    public static long getWaitTimeExp(int retryCount) {
        final long retryMultiplier = 100L;
        return (long)Math.pow(2, retryCount) * retryMultiplier;
    }

    public static String getCacheDirectory(INIConfiguration config) {
        return config.getString("cache-dir", System.getProperty("user.home") + File.separator + ".dockstore" + File.separator + "cache");
    }

    private static boolean isCacheOn(INIConfiguration config) {
        final String useCache = config.getString("use-cache", "false");
        return "true".equalsIgnoreCase(useCache) || "use".equalsIgnoreCase(useCache) || "T".equalsIgnoreCase(useCache);
    }

    public static void main(String[] args) {
        String userHome = System.getProperty("user.home");
        PluginManager manager = FileProvisionUtil
                .getPluginManager(Utilities.parseConfig(userHome + File.separator + ".dockstore" + File.separator + "config"));

        List<ProvisionInterface> greetings = manager.getExtensions(ProvisionInterface.class);
        for (ProvisionInterface provision : greetings) {
            System.out.println("Plugin: " + provision.getClass().getName());
            System.out.println("\tSchemes handled: " + provision.getClass().getName());
            for (String prefix : provision.schemesHandled()) {
                System.out.println("\t\t " + prefix);
            }
        }
    }

    /**
     * This method downloads both local and remote files into the working directory
     *
     * @param targetPath path for target file
     * @param localPath  the absolute path where we will download files to
     */
    public void provisionInputFile(String targetPath, Path localPath) {

        Path potentialCachedFile = null;
        final boolean useCache = isCacheOn(config);
        // check if a file exists in the cache and if it does, link/copy it into place
        if (useCache) {
            // check cache for cached files
            final String cacheDirectory = getCacheDirectory(config);
            // create cache directory
            final Path cachePath = Paths.get(cacheDirectory);
            if (Files.notExists(cachePath)) {
                if (!cachePath.toFile().mkdirs()) {
                    throw new RuntimeException("Could not create dockstore cache: " + cacheDirectory);
                }
            }

            final String sha1 = DigestUtils.sha1Hex(targetPath);
            final String sha1Prefix = sha1.substring(0, 2);
            final String sha1Suffix = sha1.substring(2);
            potentialCachedFile = Paths.get(cacheDirectory, sha1Prefix, sha1Suffix);
            if (Files.exists(potentialCachedFile)) {
                System.out.println("Found file " + targetPath + " in cache, hard-linking");
                try {
                    final Path parentPath = localPath.getParent();
                    if (Files.notExists(parentPath)) {
                        Files.createDirectory(parentPath);
                    }
                    Files.createLink(localPath, potentialCachedFile);
                } catch (IOException e) {
                    LOG.error("Cannot create hard link to cached file, you may want to move your cache", e.getMessage());
                    try {
                        Files.copy(potentialCachedFile, localPath);
                    } catch (IOException e1) {
                        LOG.error("Could not copy " + targetPath + " to " + localPath, e);
                        throw new RuntimeException("Could not copy " + targetPath + " to " + localPath, e1);
                    }
                    System.out.println("Found file " + targetPath + " in cache, copied");
                }
            }
        }

        URI objectIdentifier = URI.create(targetPath);    // throws IllegalArgumentException if it isn't a valid URI
        if (objectIdentifier.getScheme() != null) {
            String scheme = objectIdentifier.getScheme().toLowerCase();
            for (ProvisionInterface provision : plugins) {
                if (provision.schemesHandled().contains(scheme.toUpperCase()) || provision.schemesHandled()
                        .contains(scheme.toLowerCase())) {
                    System.out.println("Calling on plugin " + provision.getClass().getName() + " to provision " + targetPath);
                    handleDownloadProvisionWithRetries(targetPath, localPath, provision);
                }
            }
        }
        // if a file does not exist yet, get it
        if (!Files.exists(localPath)) {
            // check if we can use a plugin
            boolean localFileType = objectIdentifier.getScheme() == null;
            if (!localFileType) {
                handleDownloadProvisionWithRetries(targetPath, localPath, null);
            } else {
                // hard link into target location
                Path actualTargetPath = null;
                try {
                    String workingDir = System.getProperty("user.dir");
                    if (targetPath.startsWith("/")) {
                        // absolute path
                        actualTargetPath = Paths.get(targetPath);
                    } else {
                        // relative path
                        actualTargetPath = Paths.get(workingDir, targetPath);
                    }
                    // create needed directories
                    File parentFile = localPath.toFile().getParentFile();
                    if (!parentFile.exists() && !parentFile.mkdirs()) {
                        throw new IOException("Could not create " + localPath);
                    }

                    // create link
                    Files.createLink(localPath, actualTargetPath);
                } catch (IOException e) {
                    LOG.info("Could not link " + targetPath + " to " + localPath + " , copying instead", e);
                    try {
                        if (actualTargetPath.toFile().isDirectory()) {
                            FileUtils.copyDirectory(actualTargetPath.toFile(), localPath.toFile());
                        } else {
                            Files.copy(actualTargetPath, localPath);
                        }
                    } catch (IOException e1) {
                        LOG.error("Could not copy " + targetPath + " to " + localPath, e);
                        throw new RuntimeException("Could not copy " + targetPath + " to " + localPath, e1);
                    }
                }
            }
        }

        // cache the file if we got it successfully
        if (useCache) {
            // do not cache directories
            if (localPath.toFile().isDirectory()) {
                return;
            }
            // populate cache
            if (Files.notExists(potentialCachedFile)) {
                System.out.println("Caching file " + localPath + " in cache, hard-linking");
                try {
                    // create parent directory
                    final Path parentPath = potentialCachedFile.getParent();
                    if (Files.notExists(parentPath)) {
                        Files.createDirectory(parentPath);
                    }
                    Files.createLink(potentialCachedFile, localPath);
                } catch (IOException e) {
                    LOG.error("Cannot create hard link for local file, skipping", e);
                }
            }
        }
    }

    private void handleDownloadProvisionWithRetries(String targetPath, Path localPath, ProvisionInterface provision) {
        int maxRetries = config.getInt(FILE_PROVISION_RETRIES, DEFAULT_RETRIES);
        retryWrapper(provision, targetPath, localPath, maxRetries, true);
    }

    private void handleUploadProvisionWithRetries(String targetPath, Path localPath, ProvisionInterface provision, String metadata) {
        int maxRetries = config.getInt(FILE_PROVISION_RETRIES, DEFAULT_RETRIES);
        retryWrapper(provision, targetPath, localPath, maxRetries, false);
    }

    static void retryWrapper(ProvisionInterface provisionInterface, String targetPath, Path destinationPath, int maxRetries, boolean download) {
        retryWrapper(provisionInterface, targetPath, destinationPath, maxRetries, download, null);
    }

    static void retryWrapper(ProvisionInterface provisionInterface, String targetPath, Path destinationPath, int maxRetries, boolean download, String metadata) {
        if (provisionInterface == null) {
            provisionInterface = new FileProvisionUtilPluginWrapper();
        }
        boolean success;
        int retries = 0;
        do {
            if (retries > 0) {
                long waitTime = getWaitTimeExp(retries);
                System.err.print("Waiting for " + waitTime + " milliseconds due to failure\n");
                // Wait for the result.
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    throw new RuntimeException("Could not wait for retry");
                }
            }
            if (download) {
                success = provisionInterface.downloadFrom(targetPath, destinationPath);
            } else {
                // note that this is reversed
                success = provisionInterface.uploadTo(targetPath, destinationPath, Optional.ofNullable(metadata));
            }

            if (!success) {
                LOG.error("Could not provision " + targetPath + " to " + destinationPath + " , for retry " + retries);
            }
        } while (!success && retries++ < maxRetries);
        if (!success) {
            throw new RuntimeException("Could not provision: " + targetPath + " to " + destinationPath);
        }
    }

    /**
     * Copies files from srcPath to destPath
     *
     * @param srcPath source file
     * @param info    destination and metddata associated with one file
     */
    public void registerOutputFile(String srcPath, FileInfo info) {
        this.registeredFiles.add(ImmutablePair.of(srcPath, info));
    }

    /**
     * Copies files from srcPath to destPath
     *
     * @param srcPath            source file
     * @param destPath           destination file
     * @param metadata           metaddata associated with one file
     * @param provisionInterface plugin used for this file
     */
    private void provisionOutputFile(String srcPath, String destPath, String metadata, ProvisionInterface provisionInterface) {
        File sourceFile = new File(srcPath);

        if (provisionInterface != null) {
            if (sourceFile.isDirectory()) {
                // file provisioning plugins do not really support directories
                return;
            }
            System.out.println("Calling on plugin " + provisionInterface.getClass().getName() + " to provision from " + srcPath + " to " + destPath);
            handleUploadProvisionWithRetries(destPath, Paths.get(srcPath), provisionInterface, metadata);
            // finalize output from the printer
            System.out.println();
        } else {
            try {
                FileSystemManager fsManager = VFS.getManager();
                ((DefaultFileSystemManager)fsManager).setBaseFile(Paths.get("").toFile());

                File destinationFile = new File(destPath);
                // if it is a URL, we need to treat it differently
                try {
                    URI uri = URI.create(destPath);
                    destinationFile = new File(uri);
                } catch (IllegalArgumentException e) {
                    // do nothing
                    LOG.debug(destPath + " not a uri");
                }
                try (FileObject dest = fsManager.resolveFile(destinationFile.getAbsolutePath());
                        FileObject src = fsManager.resolveFile(sourceFile.getAbsolutePath())) {
                    System.out.println("Provisioning from " + srcPath + " to " + destPath);
                    if (src.isFolder()) {
                        FileObject[] files = src.findFiles(new AllFileSelector());
                        for (FileObject file : files) {
                            FileName name = file.getName();
                            String relativePath = name.getURI().replace(src.getName().getURI(), "");
                            FileObject nestedFile = fsManager.resolveFile(destinationFile + relativePath);
                            if (file.isFolder()) {
                                System.out.println("Creating folder from " + file + " to " + nestedFile);
                                nestedFile.createFolder();
                                continue;
                            }
                            nestedFile.createFile();
                            System.out.println("Provisioning from nested file " + file + " to " + nestedFile);
                            FileProvisionUtil.copyFromInputStreamToOutputStream(file, nestedFile);
                        }
                    } else {
                        // trigger a copy from the URL to a local file path that's a UUID to avoid collision
                        // check for a local file path
                        FileProvisionUtil.copyFromInputStreamToOutputStream(src, dest);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Could not provision output files", e);
                }
            } catch (IOException e) {
                LOG.error(e.getMessage());
            }
        }
    }

    public void uploadFiles() {
        Multimap<ProvisionInterface, Pair<String, FileInfo>> map = identifyPlugins();
        Map<ProvisionInterface, Collection<Pair<String, FileInfo>>> provisionInterfaceCollectionMap = map.asMap();
        for (Map.Entry<ProvisionInterface, Collection<Pair<String, FileInfo>>> entry : provisionInterfaceCollectionMap.entrySet()) {
            ProvisionInterface pInterface = entry.getKey();
            Pair<String, FileInfo>[] pairs = entry.getValue().toArray(new Pair[entry.getValue().size()]);
            List<Optional<String>> metadataList = Stream.of(pairs).map(pair -> Optional.ofNullable(pair.getValue().getMetadata()))
                    .collect(Collectors.toList());
            List<Path> srcList = Stream.of(pairs).map(pair -> Paths.get(pair.getKey())).collect(Collectors.toList());
            List<String> destList = Stream.of(pairs).map(pair -> {
                String targetLocation = pair.getValue().getUrl();
                if (pair.getValue().isDirectory()) {
                    if (!targetLocation.endsWith("/")) {
                        targetLocation = targetLocation + '/';
                    }
                    return targetLocation + FilenameUtils.getName(pair.getKey());
                } else {
                    return targetLocation;
                }
            }).collect(Collectors.toList());

            try {
                if (pInterface != null) {
                    pInterface.prepareFileSet(destList, srcList, metadataList);
                }
                for (int i = 0; i < pairs.length; i++) {
                    Pair<String, FileInfo> pair = pairs[i];
                    String dest = destList.get(i);
                    this.provisionOutputFile(pair.getLeft(), dest, pair.getRight().getMetadata(), pInterface);
                }
                if (pInterface != null) {
                    pInterface.finalizeFileSet(destList, srcList, metadataList);
                }
            } catch (Exception e) {
                LOG.error("plugin threw an exception", e);
                throw new RuntimeException("plugin threw an exception", e);
            }
        }

    }

    private Multimap<ProvisionInterface, Pair<String, FileInfo>> identifyPlugins() {
        Multimap<ProvisionInterface, Pair<String, FileInfo>> map = ArrayListMultimap.create();

        for (ImmutablePair<String, FileInfo> pair : this.registeredFiles) {
            String destPath = pair.getRight().getUrl();
            URI objectIdentifier = URI.create(destPath);    // throws IllegalArgumentException if it isn't a valid URI
            boolean handled = false;
            if (objectIdentifier.getScheme() != null) {
                String scheme = objectIdentifier.getScheme();
                for (ProvisionInterface provision : plugins) {
                    if (provision.schemesHandled().contains(scheme.toUpperCase()) || provision.schemesHandled()
                            .contains(scheme.toLowerCase())) {
                        map.put(provision, pair);
                        handled = true;
                    }
                }
            }
            if (!handled) {
                map.put(null, pair);
            }
        }
        return map;
    }

    /**
     * Describes a single File
     */
    public static class FileInfo {
        private String localPath;
        private String url;
        private String metadata;
        private boolean directory;

        public String getLocalPath() {
            return localPath;
        }

        public void setLocalPath(String localPath) {
            this.localPath = localPath;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getMetadata() {
            return metadata;
        }

        public void setMetadata(String metadata) {
            this.metadata = metadata;
        }

        public boolean isDirectory() {
            return directory;
        }

        public void setDirectory(boolean directory) {
            this.directory = directory;
        }
    }

    /**
     * Create a facade to treat normal vfs2 downloading as a plugin
     */
    public static class FileProvisionUtilPluginWrapper implements ProvisionInterface {

        @Override
        public Set<String> schemesHandled() {
            return null;
        }

        @Override
        public boolean downloadFrom(String sourcePath, Path destination) {
            return FileProvisionUtil.downloadFromVFS2(sourcePath, destination);
        }

        @Override
        public boolean uploadTo(String destPath, Path sourceFile, Optional<String> metadata) {
            return false;
        }

        @Override
        public void setConfiguration(Map<String, String> config) {
            /*** do nothing */
        }
    }
}

