package org.apache.maven.plugins.toolchain;


import org.apache.maven.monitor.logging.DefaultLog;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.junit.Test;

public class FoojayServiceTest {
    FoojayService foojayService = new FoojayService(new DefaultLog(new ConsoleLogger()), null);

    @Test
    public void testParseDownloadUrl() throws Exception {
        final String[] jdkLoadUrl = foojayService.parseFileNameAndDownloadUrl("17", "oracle-open-jdk");
        System.out.println("filename: " + jdkLoadUrl[0]);
        System.out.println("url: " + jdkLoadUrl[1]);
    }

    @Test
    public void testParseGraalVMDownloadUrl() throws Exception {
        final String[] jdkLoadUrl = foojayService.parseFileNameAndDownloadUrl("22.2", "graalvm_ce17");
        System.out.println("filename: " + jdkLoadUrl[0]);
        System.out.println("url: " + jdkLoadUrl[1]);
    }

    @Test
    public void testDownloadAndExtract() throws Exception {
        foojayService.downloadAndExtractJdk("15", "oracle_open_jdk");
    }
}
