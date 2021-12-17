package org.apache.maven.plugins.toolchain;

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

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.toolchain.MisconfiguredToolchainException;
import org.apache.maven.toolchain.ToolchainManagerPrivate;
import org.apache.maven.toolchain.ToolchainPrivate;
import org.apache.maven.toolchain.java.DefaultJavaToolChain;
import org.apache.maven.toolchain.model.ToolchainModel;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Check that toolchains requirements are met by currently configured toolchains and
 * store the selected toolchains in build context for later retrieval by other plugins.
 *
 * @author mkleint
 */
@Mojo(name = "toolchain", defaultPhase = LifecyclePhase.VALIDATE,
        configurator = "toolchains-requirement-configurator")
public class ToolchainMojo extends AbstractMojo {

    /**
     *
     */
    @Component
    private ToolchainManagerPrivate toolchainManagerPrivate;

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Toolchains requirements, specified by one
     * <pre>  &lt;toolchain-type&gt;
     *    &lt;param&gt;expected value&lt;/param&gt;
     *    ...
     *  &lt;/toolchain-type&gt;</pre>
     * element for each required toolchain.
     */
    @Parameter(required = true)
    private ToolchainsRequirement toolchains;
    /**
     * skip toolchains or not, or use -Dtoolchain.skip
     */
    @Parameter(property = "skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute()
            throws MojoExecutionException, MojoFailureException {
        final String toolchainsSkip = System.getProperty("toolchain.skip");
        if (toolchainsSkip != null) {
            skip = Boolean.parseBoolean(toolchainsSkip);
        }
        if (skip) {
            getLog().info("toolchain plugin skipped.");
            return;
        }
        if (toolchains == null) {
            // should not happen since parameter is required...
            getLog().warn("No toolchains requirements configured.");
            return;
        }

        List<String> nonMatchedTypes = new ArrayList<>();

        for (Map.Entry<String, Map<String, String>> entry : toolchains.getToolchains().entrySet()) {
            String type = entry.getKey();

            if (!selectToolchain(type, entry.getValue())) {
                nonMatchedTypes.add(type);
            }
        }

        if (!nonMatchedTypes.isEmpty()) {
            // TODO add the default toolchain instance if defined??
            StringBuilder buff = new StringBuilder();
            buff.append("Cannot find matching toolchain definitions for the following toolchain types:");

            for (String type : nonMatchedTypes) {
                buff.append(System.lineSeparator());
                buff.append(getToolchainRequirementAsString(type, toolchains.getParams(type)));
            }

            getLog().error(buff.toString());

            throw new MojoFailureException(buff.toString() + System.lineSeparator()
                    + "Please make sure you define the required toolchains in your ~/.m2/toolchains.xml file.");
        }
    }

    protected String getToolchainRequirementAsString(String type, Map<String, String> params) {
        StringBuilder buff = new StringBuilder();

        buff.append(type).append(" [");

        if (params.size() == 0) {
            buff.append(" any");
        } else {
            for (Map.Entry<String, String> param : params.entrySet()) {
                buff.append(" ").append(param.getKey()).append("='").append(param.getValue());
                buff.append("'");
            }
        }

        buff.append(" ]");

        return buff.toString();
    }

    protected boolean selectToolchain(String type, Map<String, String> params)
            throws MojoExecutionException {
        getLog().info("Required toolchain: " + getToolchainRequirementAsString(type, params));
        int typeFound = 0;

        try {
            ToolchainPrivate[] tcs = getToolchains(type);

            for (ToolchainPrivate tc : tcs) {
                if (!type.equals(tc.getType())) {
                    // useful because of MNG-5716
                    continue;
                }

                typeFound++;

                if (tc.matchesRequirements(params)) {
                    getLog().info("Found matching toolchain for type " + type + ": " + tc);

                    // store matching toolchain to build context
                    toolchainManagerPrivate.storeToolchainToBuildContext(tc, session);

                    return true;
                }
            }
        } catch (MisconfiguredToolchainException ex) {
            throw new MojoExecutionException("Misconfigured toolchains.", ex);
        }
        //no toolchain found
        if (type.equalsIgnoreCase("jdk")) {
            String version = params.get("version");
            String vendor = params.get("vendor");
            ToolchainPrivate tc = null;
            if (vendor == null || vendor.isEmpty()) {
                vendor = "oracle_open_jdk";
            }
            //jbang check first
            if (vendor.equalsIgnoreCase("oracle_open_jdk")) {
                final String userHome = System.getProperty("user.home");
                File jbangHome = new File(userHome, ".jbang");
                if (jbangHome.exists()) {
                    tc = findJdkFromJbang(jbangHome, version, vendor);
                }
            }
            //install JDK automatically
            if (tc == null) {
                tc = autoInstallJdk(version, vendor);
            }
            if (tc != null) {
                toolchainManagerPrivate.storeToolchainToBuildContext(tc, session);
                return true;
            }
        }
        getLog().error("No toolchain " + ((typeFound == 0) ? "found" : ("matched from " + typeFound + " found"))
                + " for type " + type);

        return false;
    }

    private ToolchainPrivate[] getToolchains(String type)
            throws MojoExecutionException, MisconfiguredToolchainException {
        return toolchainManagerPrivate.getToolchainsForType(type, session);
    }

    /**
     * install JDK and modify toolchains.xml automatically
     *
     * @param version version
     * @param vendor  vendor
     * @return toolchain
     */
    private ToolchainPrivate autoInstallJdk(String version, String vendor) {
        FoojayService foojayService = new FoojayService(getLog());
        try {
            File jdkHome = foojayService.downloadAndExtractJdk(version, vendor);
            if (jdkHome != null) {
                return addJDKToToolchains(jdkHome, version, vendor);
            }
        } catch (Exception e) {
            getLog().error("Failed to download and install JDK", e);
        }
        return null;
    }

    private ToolchainPrivate findJdkFromJbang(File jbangHome, String version, String vendor) {
        try {
            String majorVersion = version;
            if (majorVersion.contains(".")) {
                if (version.startsWith("1.")) {
                    majorVersion = "8";
                } else {
                    majorVersion = version.substring(0, version.indexOf("."));
                }
            }
            File jdkHome = new File(jbangHome, "cache/jdks/" + majorVersion);
            if (!jdkHome.exists()) {
                System.out.println("jbang install " + majorVersion);
                String jbangCmd = "bin/jbang";
                if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                    jbangCmd = "bin/jbang.cmd";
                }
                final Process process = new ProcessBuilder(new File(jbangHome, jbangCmd).toString(), "jdk", "install", majorVersion).start();
                process.waitFor();
            }
            return addJDKToToolchains(jdkHome, version, vendor);
        } catch (Exception e) {
            getLog().error("Failed to find JDK from jbang", e);
        }
        return null;
    }

    private ToolchainPrivate addJDKToToolchains(File jdkHome, String version, String vendor) throws Exception {
        final ToolchainPrivate javaToolChain = buildJdkToolchain(version, vendor, jdkHome.getAbsolutePath());
        File toolchainsXml = new File(new File(System.getProperty("user.home")), ".m2/toolchains.xml");
        Xpp3Dom toolchainsDom;
        if (toolchainsXml.exists()) {
            toolchainsDom = Xpp3DomBuilder.build(new FileReader(toolchainsXml));
        } else {
            toolchainsDom = new Xpp3Dom("toolchains");
        }
        toolchainsDom.addChild(jdkToolchainDom(version, vendor, jdkHome.getAbsolutePath()));
        final FileWriter writer = new FileWriter(toolchainsXml);
        Xpp3DomWriter.write(writer, toolchainsDom);
        writer.close();
        return javaToolChain;
    }

    private ToolchainPrivate buildJdkToolchain(String version, String vendor, String jdkHome) {
        ToolchainModel toolchainModel = new ToolchainModel();
        toolchainModel.setType("jdk");
        Properties provides = new Properties();
        provides.setProperty("version", version);
        provides.setProperty("vendor", vendor);
        toolchainModel.setProvides(provides);
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        configuration.addChild(createElement("jdkHome", jdkHome));
        toolchainModel.setConfiguration(configuration);
        DefaultJavaToolChain javaToolChain = new DefaultJavaToolChain(toolchainModel, new ConsoleLogger());
        javaToolChain.setJavaHome(jdkHome);
        return javaToolChain;
    }

    private Xpp3Dom jdkToolchainDom(String version, String vendor, String jdkHome) {
        Xpp3Dom toolchainDom = new Xpp3Dom("toolchain");
        toolchainDom.addChild(createElement("type", "jdk"));
        Xpp3Dom providesDom = new Xpp3Dom("provides");
        providesDom.addChild(createElement("version", version));
        providesDom.addChild(createElement("vendor", vendor));
        Xpp3Dom configurationDom = new Xpp3Dom("configuration");
        configurationDom.addChild(createElement("jdkHome", jdkHome));
        toolchainDom.addChild(providesDom);
        toolchainDom.addChild(configurationDom);
        return toolchainDom;
    }

    private Xpp3Dom createElement(String name, String value) {
        Xpp3Dom dom = new Xpp3Dom(name);
        dom.setValue(value);
        return dom;
    }

}
