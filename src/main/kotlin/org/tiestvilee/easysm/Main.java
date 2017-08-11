package org.tiestvilee.easysm;

import org.apache.ivy.Ivy;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.parser.ModuleDescriptorParser;
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry;
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter;
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
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;

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

            IvyNode root = dependency.getRoot();
            while (true) {
                IvyNode oldRoot = root;
                root = root.getRoot();
                if (root == oldRoot) {
                    break;
                }
            }
            System.out.println("mvn:" + id.getOrganisation() + ":" + id.getName() + ":jar:" + id.getRevision());
        }

        ivy.getLoggerEngine().popLogger();
        ivy.popContext();
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
            ivy.getLoggerEngine().pushLogger(new DefaultMessageLogger(Message.MSG_ERR));
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
