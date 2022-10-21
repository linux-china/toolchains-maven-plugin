package org.apache.maven.plugins.toolchain;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Proxy;
import org.codehaus.plexus.archiver.AbstractUnArchiver;
import org.codehaus.plexus.archiver.tar.TarGZipUnArchiver;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

public class FoojayService {
    private final HttpClient httpClient;

    private final Log log;

    public FoojayService(Log log, Proxy proxy) {
        this.log = log;
        // https://maven.apache.org/guides/mini/guide-proxies.html
        if (proxy != null) {
            final HttpClientBuilder builder = HttpClients.custom();
            builder.setProxy(new HttpHost(proxy.getHost(), proxy.getPort(), proxy.getProtocol()));
            if (proxy.getUsername() != null) {
                Credentials credentials = new UsernamePasswordCredentials(proxy.getUsername(), proxy.getPassword());
                AuthScope authScope = new AuthScope(proxy.getHost(), proxy.getPort());
                CredentialsProvider credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(authScope, credentials);
                builder.setDefaultCredentialsProvider(credsProvider);
            }
            httpClient = builder.build();
        } else {
            httpClient = HttpClients.createDefault();
        }
    }

    public Path downloadAndExtractJdk(String version, String vendor) throws Exception {
        log.info("Begin to install JDK " + version);
        final String[] fileNameAndDownloadUrl = parseFileNameAndDownloadUrl(version, vendor);
        if (fileNameAndDownloadUrl == null) {
            return null;
        }
        String jdkFileName = fileNameAndDownloadUrl[0];
        String downloadUrl = fileNameAndDownloadUrl[1];
        final Path userHome = Paths.get(System.getProperty("user.home"));
        Path jdksDir = userHome.resolve(".m2").resolve("jdks");
        if (!jdksDir.toFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            jdksDir.toFile().mkdir();
        }
        Path jdkHome = downloadAndExtract(downloadUrl, jdkFileName, jdksDir);
        if (jdkHome.resolve("Contents").resolve("Home").toFile().exists()) {  // mac tgz
            jdkHome = jdkHome.resolve("Contents").resolve("Home");
        }
        log.info("JDK installed: " + jdkHome.toAbsolutePath());
        if (vendor.contains("graalvm")) {
            Path guBin = jdkHome.resolve("bin").resolve("gu");
            ProcessBuilder pb = new ProcessBuilder(guBin.toAbsolutePath().toString(), "install", "native-image", "--ignore");
            pb.environment().put("GRAALVM_HOME", jdkHome.toAbsolutePath().toString());
            pb.start();
        }
        return jdkHome;
    }

    public String[] parseFileNameAndDownloadUrl(String version, String vendor) throws Exception {
        String os = getOsName();
        String archiveType = "tar.gz";
        if (os.equals("windows")) {
            archiveType = "zip";
        }
        String archName = getArchName();
        String bitness = archName.equals("x32") ? "32" : "64";
        String libcType;
        switch (os) {
            case "linux":
                libcType = "glibc";
                break;
            case "windows":
                libcType = "c_std_lib";
                break;
            case "macos":
                libcType = "libc";
                break;
            default:
                libcType = "";
                break;
        }
        String queryUrl = "https://api.foojay.io/disco/v3.0/packages?"
                + "distribution=" + vendor
                + "&version=" + version
                + "&operating_system=" + os
                + "&architecture=" + archName
                + "&bitness=" + bitness
                + "&archive_type=" + archiveType
                + "&libc_type=" + libcType
                + "&latest=overall&package_type=jdk&discovery_scope_id=directly_downloadable&match=any&javafx_bundled=false&directly_downloadable=true&release_status=ga";
        HttpGet request = new HttpGet(queryUrl);
        final HttpResponse response = httpClient.execute(request);
        if (response.getStatusLine().getStatusCode() == 200) {
            Gson gson = new Gson();
            final JsonObject jsonObject = gson.fromJson(EntityUtils.toString(response.getEntity()), JsonElement.class).getAsJsonObject();
            final JsonObject pkgJson = jsonObject.getAsJsonArray("result").get(0).getAsJsonObject();
            String pkgInfoUri = pkgJson.getAsJsonObject("links").get("pkg_info_uri").getAsString();
            HttpGet pkgInfoGet = new HttpGet(pkgInfoUri);
            final HttpResponse pkgInfoResponse = httpClient.execute(pkgInfoGet);
            if (pkgInfoResponse.getStatusLine().getStatusCode() == 200) {
                final JsonObject pkgInfoJson = gson.fromJson(EntityUtils.toString(pkgInfoResponse.getEntity()), JsonElement.class).getAsJsonObject();
                String downloadUrl = pkgInfoJson.getAsJsonArray("result").get(0).getAsJsonObject().get("direct_download_uri").getAsString();
                return new String[]{pkgJson.get("filename").getAsString(), downloadUrl};
            }
        }
        return null;
    }

    private Path downloadAndExtract(String link, String fileName, Path destDir) throws Exception {
        File destFile = destDir.resolve(fileName).toFile();
        if (!destFile.exists()) {
            log.info("Download " + fileName + " from " + link);
            FileUtils.copyURLToFile(new URL(link), destFile);
        }
        log.info("Extract " + fileName);
        String extractDir = getRootNameInArchive(destFile);
        extractArchiveFile(destFile, destDir.toFile());
        //noinspection ResultOfMethodCallIgnored
        destFile.delete();
        return destDir.resolve(extractDir);
    }

    private String getRootNameInArchive(File archiveFile) throws Exception {
        ArchiveInputStream archiveInputStream;
        if (archiveFile.getName().endsWith("tar.gz") || archiveFile.getName().endsWith("tgz")) {
            archiveInputStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(archiveFile)));
        } else {
            archiveInputStream = new ZipArchiveInputStream((new FileInputStream(archiveFile)));
        }
        String name = archiveInputStream.getNextEntry().getName();
        while (name.startsWith(".") && name.length() < 4) {  // fix '.._' bug
            name = archiveInputStream.getNextEntry().getName();
        }
        archiveInputStream.close();
        if (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.contains("/")) {
            name = name.substring(0, name.indexOf("/"));
        }
        return name;
    }


    private void extractArchiveFile(File sourceFile, File destDir) {
        String fileName = sourceFile.getName();
        final AbstractUnArchiver unArchiver;
        if (fileName.endsWith(".tgz") || fileName.endsWith(".tar.gz")) {
            unArchiver = new TarGZipUnArchiver();
        } else {
            unArchiver = new ZipUnArchiver();
        }
        unArchiver.enableLogging(new ConsoleLogger(Logger.LEVEL_ERROR, "console"));
        unArchiver.setSourceFile(sourceFile);
        unArchiver.setDestDirectory(destDir);
        unArchiver.setOverwrite(true);
        unArchiver.extract();
    }

    private String getOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            return "macos";
        } else if (os.contains("windows")) {
            return "windows";
        } else {
            return "linux";
        }
    }

    private String getArchName() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("x86_32") || arch.contains("amd32")) {
            arch = "x32";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            arch = "aarch64";
        } else {
            arch = "x64";
        }
        return arch;
    }
}
