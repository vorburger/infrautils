/*
 * Copyright (c) 2017 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.infrautils.caches.sample.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.when;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionKitConfigurationOption.Platform.NIX;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionKitConfigurationOption.Platform.WINDOWS;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.configureConsole;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import javax.inject.Inject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opendaylight.infrautils.caches.sample.SampleService;
import org.ops4j.io.FileUtils;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.karaf.options.KarafDistributionKitConfigurationOption;
import org.ops4j.pax.exam.karaf.options.KarafDistributionOption;
import org.ops4j.pax.exam.options.MavenUrlReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example Integration Test for cached function.
 * @author Michael Vorburger.ch
 */
@RunWith(PaxExam.class)
// @ExamReactorStrategy(PerClass.class) IFF the default PerMethod (which provides better isolation!) really is too slow?
@SuppressFBWarnings("NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR")
public class SampleIntegrationTest {

    // TODO move this into a re-usable abstract parent class; similar to
    //  but simpler than org.opendaylight.controller.config.it.base.AbstractConfigTestBase (without CSS),

    // TODO @RunWith(PaxExamParameterized) to run under both Karaf 3 & 4 ?

    // TODO integrate this with bundle[4]-test to ensure all bundles have finished wiring before test start, if needed?
    //        and/or use @Inject protected [private?] org.apache.karaf.features.BootFinished bootFinished; ?


    // TODO Jacoco support, for (only?) build on Sonar, see Netvirt IT
    // TODO EclEmma support for coverage view in Eclipse

    // TODO util to customize logging with a NICE and SIMPLE API.. Class<?> or String

    private static final Logger LOG = LoggerFactory.getLogger(SampleIntegrationTest.class);

    // NB: The sys.prop. named "maven.repo.local" is the Maven standard one
    private static final String MAVEN_REPO_LOCAL = "maven.repo.local";
    private static final String KARAF_DEBUG_PROP = "karaf.debug";
    private static final String KARAF_DEBUG_PORT = "5005";

    @Configuration
    public Option[] config() {
        // check that the Karaf distribution is not on the classpath:
        try {
            getClass().getClassLoader().loadClass("org.apache.karaf.main.Main");
            fail("Found Karaf's main class on the test's classpath; remove <dependency> to Karaf JARs in the POM!"
                    + " (just the zip/tar.gz distribution, with excludes, is fine however,"
                    + " as that doesn't pollute the classpath)");
        } catch (ClassNotFoundException e) {
            // Good!
        }

        final File targetPaxExam = new File("target/paxexam/");
        FileUtils.delete(targetPaxExam);

        final boolean isKaraf4 = true; // TODO more dynamic & self test both
        final String karafArtifactId = isKaraf4 ? "opendaylight-karaf4-empty" : "opendaylight-karaf-empty";
        // NB: This karafVersion currently actually doesn't seem to be used by PAX Exam.. ;) but set anyway:
        // (see also https://ops4j1.jira.com/projects/PAXEXAM/issues/PAXEXAM-598)
        final String karafVersion = isKaraf4 ? "4.0.7" : "3.0.0";

        // NB the tar.gz is almost half the size of the zip, so use that, even for Windows (works fine)
        MavenUrlReference karafURL = maven().groupId("org.opendaylight.odlparent")
                .artifactId(karafArtifactId).versionAsInProject().type("tar.gz");
        // TODO https://ops4j1.jira.com/browse/PAXEXAM-813
        // String? karafURL = [url(]"link:classpath:" + karafArtifactId + ".link";
        LOG.info("Karaf used: {}", karafURL.toString());

        return new Option[] {
            // TODO find another solution without, for better isolation
            // probably going to use http://veithen.github.io/alta/
            // TODO Test and document what this is used for...
            editKarafConfigurationFile(MAVEN_REPO_LOCAL, "etc/org.ops4j.pax.url.mvn.cfg",
                    "org.ops4j.pax.url.mvn.localRepository", System.getProperty(MAVEN_REPO_LOCAL, "")),

            when(Boolean.getBoolean(KARAF_DEBUG_PROP))
                .useOptions(KarafDistributionOption.debugConfiguration(KARAF_DEBUG_PORT, true)),

            new KarafDistributionKitConfigurationOption(karafURL, NIX)
                .karafVersion(karafVersion)
                .useDeployFolder(false).unpackDirectory(targetPaxExam),
            new KarafDistributionKitConfigurationOption(karafURL, WINDOWS)
                .karafVersion(karafVersion)
                .useDeployFolder(false).unpackDirectory(targetPaxExam),

            // DO *NOT* add new VMOption() for -Xmx, -XX:*, java.security.egd etc. here! All those should
            // just be in our standard launch scripts, which by KarafDistributionKitConfigurationOption we ARE using!
            //
            // Likewise, please DO *NOT* add other magic here, such as mavenBundle("org.apache.aries.quiesce") or
            // systemPackages() - any tweaks like that should either be in the standard ODL empty Karaf distribution
            // in odlparent, or not needed!!

            // keeping it around can be handy to go poke around it manually after test failures;
            // NB that this very quickly fills up your disk with tens of thousands of files,
            // which is why we have it on by default (easier than having to remember to set a property..),
            // but we wipe it out before every test run (above)
            keepRuntimeFolder(),

            // TODO bug: it looks like this option is ignored when using KarafDistributionKitConfigurationOption?
            configureConsole()
                .ignoreLocalConsole()  // no useless console, as under Karaf 4 the ANSI Art colour codes (banner)
                                       // screw up the Console view in Eclipse when running test there... :(
                .ignoreRemoteShell(),  // remoteShell defaults to true (?), so save time, as not required

            // TODO remove this when wrappedBundle(Truth) below is removed (it's just for that; auto.w.odl-testutils)
            when(isKaraf4)
                .useOptions(
                    features(maven("org.apache.karaf.features","standard", karafVersion)
                                .classifier("features").type("xml"), "wrap")),

            // TODO Experiment with not needing this by scanning this call via reflection, find dependencies,
            // and injecting 'em all via a @ProbeBuilder probe.addTest ... that could be cool!
            // or https://ops4j1.jira.com/projects/PAXEXAM/issues/PAXEXAM-543 ?
            // wrappedBundle(maven("com.google.truth", "truth").versionAsInProject()),
            // TODO Guava is "just" a dependency of Truth; we should have an odl-infrautils-testutils feature instead..
            mavenBundle(maven("com.google.guava", "guava").versionAsInProject()),

            // TODO don't specify this here like this, but in pom.xml and read from there (veithen/alta ?)
            features(maven("org.opendaylight.infrautils","infrautils-features4", "1.3.0-SNAPSHOT")
                    .classifier("features").type("xml"), "odl-infrautils-caches-sample"),

            // TODO why does this, intentionally with a bad feature name, not fail the test???
            //features(maven("org.opendaylight.infrautils","infrautils-features4", "1.1.0-SNAPSHOT")
            //       .classifier("features").type("xml"), "odl-infrautils-caches-sampleXXX")

            // TODO now that we're feature based, this can probably just be removed?
            // TODO find this automatically from dependencies.properties!
            mavenBundle(maven("org.opendaylight.infrautils", "caches-sample").versionAsInProject()),
        };
    }
/*
    TODO revisit exploring this approach, perhaps combined with above...
    The idea here is that if we can find a dependency on the classpath,
    and if we set up the classpath correctly in an IDE (like Eclipse),
    meaning including e.g. target/generated-resources/org/opendaylight/blueprint/
    then this test could find the latest live version in-workspace, instead of
    having to rely on a likely stale version last installed into the Maven repo:

    @ProbeBuilder
    public TestProbeBuilder probeConfiguration(TestProbeBuilder probe) {
        probe.addTest(SampleService.class);
        return probe;
    }
*/

    private Option editKarafConfigurationFile(String source, String configurationFilePath, String key, String value) {
        LOG.warn("{}: In {} change {} = {}", source, configurationFilePath, key, value);
        return KarafDistributionOption.editConfigurationFilePut(configurationFilePath, key, value);
    }

    // TODO @Rule LogRule

    // TODO ClasspathHellDuplicatesCheckRule https://git.opendaylight.org/gerrit/#/c/50851/

    @Inject SampleService sampleService;

    @Test
    public void testEmptyJustToMakeSureKarafStartedOK() {
        LOG.info("info log is not visible, as not enabled");
        LOG.warn("warn log is visible");

        assertEquals("hello, world", sampleService.sayHello("world"));
    }

    // TODO @Test public void testCacheCLI() {
}
