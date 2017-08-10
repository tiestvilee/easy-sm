package org.tiestvilee.easysm;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.cache.ResolutionCacheManager;
import org.apache.ivy.core.deliver.DeliverOptions;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.publish.PublishOptions;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.resolve.ResolvedModuleRevision;
import org.apache.ivy.core.retrieve.RetrieveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.parser.ModuleDescriptorParser;
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter;
import org.apache.ivy.plugins.report.XmlReportParser;
import org.apache.ivy.plugins.repository.url.URLResource;
import org.apache.ivy.util.DefaultMessageLogger;
import org.apache.ivy.util.Message;
import org.apache.ivy.util.cli.CommandLine;
import org.apache.ivy.util.cli.CommandLineParser;
import org.apache.ivy.util.cli.OptionBuilder;
import org.apache.ivy.util.cli.ParseException;
import org.apache.ivy.util.filter.FilterHelper;
import org.apache.ivy.util.url.CredentialsStore;
import org.apache.ivy.util.url.URLHandler;
import org.apache.ivy.util.url.URLHandlerDispatcher;
import org.apache.ivy.util.url.URLHandlerRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

public class Main {
    private static final int HELP_WIDTH = 80;

    static CommandLineParser getParser() {
        return new CommandLineParser()
            .addCategory("settings options")
            .addOption(
                new OptionBuilder("settings").arg("settingsfile")
                    .description("use given file for settings").create())
            .addOption(
                new OptionBuilder("cache").arg("cachedir")
                    .description("use given directory for cache").create())
            .addOption(
                new OptionBuilder("novalidate").description(
                    "do not validate ivy files against xsd").create())
            .addOption(
                new OptionBuilder("m2compatible").description("use Maven 2 compatibility")
                    .create())
            .addOption(
                new OptionBuilder("conf").arg("settingsfile").deprecated()
                    .description("use given file for settings").create())
            .addOption(
                new OptionBuilder("useOrigin")
                    .deprecated()
                    .description(
                        "use original artifact location "
                            + "with local resolvers instead of copying to the cache")
                    .create())

            .addCategory("resolve options")
            .addOption(
                new OptionBuilder("ivy").arg("ivyfile")
                    .description("use given file as ivy file").create())
            .addOption(
                new OptionBuilder("refresh").description("refresh dynamic resolved revisions")
                    .create())
            .addOption(
                new OptionBuilder("dependency")
                    .arg("organisation")
                    .arg("module")
                    .arg("revision")
                    .description(
                        "use this instead of ivy file to do the rest "
                            + "of the work with this as a dependency.").create())
            .addOption(
                new OptionBuilder("confs").arg("configurations").countArgs(false)
                    .description("resolve given configurations").create())
            .addOption(
                new OptionBuilder("types").arg("types").countArgs(false)
                    .description("accepted artifact types")
                    .create())
            .addOption(
                new OptionBuilder("mode").arg("resolvemode")
                    .description("the resolve mode to use").create())
            .addOption(
                new OptionBuilder("notransitive").description(
                    "do not resolve dependencies transitively").create())

            .addCategory("deliver options")
            .addOption(
                new OptionBuilder("deliverto").arg("ivypattern")
                    .description("use given pattern as resolved ivy file pattern").create())

            .addCategory("http auth options")
            .addOption(
                new OptionBuilder("realm").arg("realm")
                    .description("use given realm for HTTP AUTH").create())
            .addOption(
                new OptionBuilder("host").arg("host")
                    .description("use given host for HTTP AUTH").create())
            .addOption(
                new OptionBuilder("username").arg("username")
                    .description("use given username for HTTP AUTH").create())
            .addOption(
                new OptionBuilder("passwd").arg("passwd")
                    .description("use given password for HTTP AUTH").create())

            .addCategory("message options")
            .addOption(
                new OptionBuilder("debug").description("set message level to debug").create())
            .addOption(
                new OptionBuilder("verbose").description("set message level to verbose")
                    .create())
            .addOption(
                new OptionBuilder("warn").description("set message level to warn").create())
            .addOption(
                new OptionBuilder("error").description("set message level to error").create())

            .addCategory("help options")
            .addOption(new OptionBuilder("?").description("display this help").create())
            .addOption(
                new OptionBuilder("deprecated").description("show deprecated options").create())
            .addOption(
                new OptionBuilder("version").description("displays version information")
                    .create());
    }

    public static void main(String[] args) throws Exception {
        CommandLineParser parser = getParser();
        try {
            run(parser, args);
            System.exit(0);
        } catch (ParseException ex) {
            System.err.println(ex.getMessage());
            usage(parser, false);
            System.exit(1);
        }
    }

    static void run(CommandLineParser parser, String[] args) throws Exception {
        // parse the command line arguments
        CommandLine line = parser.parse(args);

        if (line.hasOption("?")) {
            usage(parser, line.hasOption("deprecated"));
            return;
        }

        if (line.hasOption("version")) {
            System.out.println("Apache Ivy " + Ivy.getIvyVersion() + " - " + Ivy.getIvyDate()
                + " :: " + Ivy.getIvyHomeURL());
            return;
        }

        boolean validate = !line.hasOption("novalidate");

        Ivy ivy = Ivy.newInstance();
        initMessage(line, ivy);
        IvySettings settings = initSettings(line, ivy);
        ivy.pushContext();

        File cache = new File(settings.substitute(line.getOptionValue("cache", settings
            .getDefaultCache().getAbsolutePath())));

        if (line.hasOption("cache")) {
            // override default cache path with user supplied cache path
            settings.setDefaultCache(cache);
        }

        if (!cache.exists()) {
            cache.mkdirs();
        } else if (!cache.isDirectory()) {
            error(cache + " is not a directory");
        }

        String[] confs;
        if (line.hasOption("confs")) {
            confs = line.getOptionValues("confs");
        } else {
            confs = new String[]{"*"};
        }

        File ivyfile;
        if (line.hasOption("dependency")) {
            String[] dep = line.getOptionValues("dependency");
            ivyfile = File.createTempFile("ivy", ".xml");
            ivyfile.deleteOnExit();
            DefaultModuleDescriptor md = DefaultModuleDescriptor
                .newDefaultInstance(ModuleRevisionId.newInstance(dep[0], dep[1] + "-caller",
                    "working"));
            DefaultDependencyDescriptor dd = new DefaultDependencyDescriptor(md,
                ModuleRevisionId.newInstance(dep[0], dep[1], dep[2]), false, false, true);
            for (String conf : confs) {
                dd.addDependencyConfiguration("default", conf);
            }
            md.addDependency(dd);
            XmlModuleDescriptorWriter.write(md, ivyfile);
            confs = new String[]{"default"};
        } else {
            ivyfile = new File(settings.substitute(line.getOptionValue("ivy", "ivy.xml")));
            if (!ivyfile.exists()) {
                error("ivy file not found: " + ivyfile);
            } else if (ivyfile.isDirectory()) {
                error("ivy file is not a file: " + ivyfile);
            }
        }

        if (line.hasOption("useOrigin")) {
            ivy.getSettings().useDeprecatedUseOrigin();
        }
        ResolveOptions resolveOptions = new ResolveOptions()
            .setConfs(confs)
            .setValidate(validate)
            .setResolveMode(line.getOptionValue("mode"))
            .setArtifactFilter(
                FilterHelper.getArtifactTypeFilter(line.getOptionValues("types")));
        if (line.hasOption("notransitive")) {
            resolveOptions.setTransitive(false);
        }
        if (line.hasOption("refresh")) {
            resolveOptions.setRefresh(true);
        }

        URL ivySource = ivyfile.toURI().toURL();

        // ResolveEngine.resolve:197
        URLResource res = new URLResource(ivySource);
        ModuleDescriptorParser parser2 = ModuleDescriptorParserRegistry.getInstance().getParser(res);
        Message.verbose("using " + parser2 + " to parse " + ivySource);
        ModuleDescriptor md = parser2.parseDescriptor(settings, ivySource, resolveOptions.isValidate());
        String revision = resolveOptions.getRevision();
        if (revision == null && md.getResolvedModuleRevisionId().getRevision() == null) {
            revision = Ivy.getWorkingRevision();
        }
        if (revision != null) {
            md.setResolvedModuleRevisionId(ModuleRevisionId.newInstance(md.getModuleRevisionId(),
                revision));
        }

        // ResolveEngine.resolve:236
        ResolveReport report = new ResolveReport(md, resolveOptions.getResolveId());
        IvyNode[] dependencies = ivy.getResolveEngine().getDependencies(md, resolveOptions, report);

        for (IvyNode dependency : dependencies) {
            ModuleRevisionId id = dependency.getModuleRevision().getId();
            System.out.println("mvn:" + id.getOrganisation() + ":" + id.getName() + ":jar:" + id.getRevision());
        }

        ivy.getLoggerEngine().popLogger();
        ivy.popContext();
    }

    /**
     * Parses the <code>cp</code> option from the command line, and returns a list of {@link File}.
     * <p>
     * All the files contained in the returned List exist, non existing files are simply skipped
     * with a warning.
     * </p>
     *
     * @param line the command line in which the cp option should be parsed
     * @return a List of files to include as extra classpath entries, or <code>null</code> if no cp
     * option was provided.
     */
    private static List<File> getExtraClasspathFileList(CommandLine line) {
        List<File> fileList = null;
        if (line.hasOption("cp")) {
            fileList = new ArrayList<>();
            for (String cp : line.getOptionValues("cp")) {
                StringTokenizer tokenizer = new StringTokenizer(cp, File.pathSeparator);
                while (tokenizer.hasMoreTokens()) {
                    String token = tokenizer.nextToken();
                    File file = new File(token);
                    if (file.exists()) {
                        fileList.add(file);
                    } else {
                        Message.warn("Skipping extra classpath '" + file
                            + "' as it does not exist.");
                    }
                }
            }
        }
        return fileList;
    }

    private static IvySettings initSettings(CommandLine line, Ivy ivy)
        throws java.text.ParseException, IOException, ParseException {
        IvySettings settings = ivy.getSettings();
        settings.addAllVariables(System.getProperties());
        if (line.hasOption("m2compatible")) {
            settings.setVariable("ivy.default.configuration.m2compatible", "true");
        }

        configureURLHandler(line.getOptionValue("realm", null), line.getOptionValue("host", null),
            line.getOptionValue("username", null), line.getOptionValue("passwd", null));

        String settingsPath = line.getOptionValue("settings", "");
        if ("".equals(settingsPath)) {
            settingsPath = line.getOptionValue("conf", "");
            if (!"".equals(settingsPath)) {
                Message.deprecated("-conf is deprecated, use -settings instead");
            }
        }
        if ("".equals(settingsPath)) {
            ivy.configureDefault();
        } else {
            File conffile = new File(settingsPath);
            if (!conffile.exists()) {
                error("ivy configuration file not found: " + conffile);
            } else if (conffile.isDirectory()) {
                error("ivy configuration file is not a file: " + conffile);
            }
            ivy.configure(conffile);
        }
        return settings;
    }

    private static void initMessage(CommandLine line, Ivy ivy) {
        if (line.hasOption("debug")) {
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_DEBUG));
        } else if (line.hasOption("verbose")) {
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_VERBOSE));
        } else if (line.hasOption("warn")) {
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_WARN));
        } else if (line.hasOption("error")) {
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_ERR));
        } else {
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_INFO));
        }
    }

    private static void outputCachePath(Ivy ivy, File cache, ModuleDescriptor md, String[] confs,
                                        String outFile) {
        try {
            StringBuilder buf = new StringBuilder();
            Collection<ArtifactDownloadReport> all = new LinkedHashSet<>();
            ResolutionCacheManager cacheMgr = ivy.getResolutionCacheManager();
            XmlReportParser parser = new XmlReportParser();
            for (String conf : confs) {
                String resolveId = ResolveOptions.getDefaultResolveId(md);
                File report = cacheMgr.getConfigurationResolveReportInCache(resolveId, conf);
                parser.parse(report);

                all.addAll(Arrays.asList(parser.getArtifactReports()));
            }
            for (ArtifactDownloadReport artifact : all) {
                if (artifact.getLocalFile() != null) {
                    buf.append(artifact.getLocalFile().getCanonicalPath());
                    buf.append(File.pathSeparator);
                }
            }

            PrintWriter writer = new PrintWriter(new FileOutputStream(outFile));
            if (buf.length() > 0) {
                buf.setLength(buf.length() - File.pathSeparator.length());
                writer.println(buf);
            }
            writer.close();
            System.out.println("cachepath output to " + outFile);

        } catch (Exception ex) {
            throw new RuntimeException("impossible to build ivy cache path: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("resource")
    private static void invoke(Ivy ivy, File cache, ModuleDescriptor md, String[] confs,
                               List<File> fileList, String mainclass, String[] args) {
        List<URL> urls = new ArrayList<>();

        // Add option cp (extra classpath) urls
        if (fileList != null && fileList.size() > 0) {
            for (File file : fileList) {
                try {
                    urls.add(file.toURI().toURL());
                } catch (MalformedURLException e) {
                    // Should not happen, just ignore.
                }
            }
        }

        try {
            Collection<ArtifactDownloadReport> all = new LinkedHashSet<>();
            ResolutionCacheManager cacheMgr = ivy.getResolutionCacheManager();
            XmlReportParser parser = new XmlReportParser();
            for (String conf : confs) {
                String resolveId = ResolveOptions.getDefaultResolveId(md);
                File report = cacheMgr.getConfigurationResolveReportInCache(resolveId, conf);
                parser.parse(report);

                all.addAll(Arrays.asList(parser.getArtifactReports()));
            }
            for (ArtifactDownloadReport artifact : all) {
                if (artifact.getLocalFile() != null) {
                    urls.add(artifact.getLocalFile().toURI().toURL());
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("impossible to build ivy cache path: " + ex.getMessage(), ex);
        }

        URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]),
            Main.class.getClassLoader());

        try {
            Class<?> c = classLoader.loadClass(mainclass);

            Method mainMethod = c.getMethod("main", String[].class);

            Thread.currentThread().setContextClassLoader(classLoader);
            mainMethod.invoke(null, new Object[]{(args == null ? new String[0] : args)});
        } catch (ClassNotFoundException cnfe) {
            throw new RuntimeException("Could not find class: " + mainclass, cnfe);
        } catch (SecurityException | NoSuchMethodException e) {
            throw new RuntimeException("Could not find main method: " + mainclass, e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("No permissions to invoke main method: " + mainclass, e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Unexpected exception invoking main method: " + mainclass, e);
        }
    }

    private static void configureURLHandler(String realm, String host, String username,
                                            String passwd) {
        CredentialsStore.INSTANCE.addCredentials(realm, host, username, passwd);

        URLHandlerDispatcher dispatcher = new URLHandlerDispatcher();
        URLHandler httpHandler = URLHandlerRegistry.getHttp();
        dispatcher.setDownloader("http", httpHandler);
        dispatcher.setDownloader("https", httpHandler);
        URLHandlerRegistry.setDefault(dispatcher);
    }

    private static void error(String msg) throws ParseException {
        throw new ParseException(msg);
    }

    private static void usage(CommandLineParser parser, boolean showDeprecated) {
        // automatically generate the help statement
        PrintWriter pw = new PrintWriter(System.out);
        parser.printHelp(pw, HELP_WIDTH, "ivy", showDeprecated);
        pw.flush();
    }

    private Main() {
    }
}