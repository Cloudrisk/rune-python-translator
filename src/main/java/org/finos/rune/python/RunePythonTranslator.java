package org.finos.rune.python;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.List;

import org.apache.commons.cli.*;

import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.Resource;
import com.regnosys.rosetta.RosettaRuntimeModule;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.xbase.lib.CollectionLiterals;
import com.regnosys.rosetta.RosettaStandaloneSetup;
import com.regnosys.rosetta.common.util.ClassPathUtils;
import com.regnosys.rosetta.common.util.UrlUtils;
import com.regnosys.rosetta.generator.external.ExternalGenerator;
import com.regnosys.rosetta.generator.external.ExternalGenerators;
import com.regnosys.rosetta.generator.python.PythonCodeGenerator;
import com.regnosys.rosetta.rosetta.RosettaModel;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import javax.inject.Provider;


/**
 * Rune to Python translator using the generator specified in the POM
 * To build and create an "executable" jar:
 *      mvn clean compile
 * To run:
 *      java -jar target/rune-python-translator-1.0-SNAPSHOT.jar -h
 * Developers:
 *      Daniel Schwartz: daniel.schwartz@ftadvisory.co
 *      Plamen Neykov: plamen.neykov@cloudrisk.com
 * Thanks to:
 *      Minesh Patel: minesh.patel@regnosys.com
 *      Hugo Hills: hugo.hills@regnosys.com
 *
 */
public class RunePythonTranslator {
    //  mvn compile exec:java -Dexec.mainClass="org.finos.rune.python.RunePythonTranslator"

    /**
     * translate Rune to Python
     * required arguments - either a source directory or a source file
     * output defaults to the current directory if not specified
     */
    public static void main(String[] args) {
        Options options   = new Options();
        Option help       = new Option("h", "usage:\n\tRunePythonGenerator --dir [source directory] --file [source file] --tgt [target directory ... defaults to .]");
        Option srcDirOpt  = createOption("s", "dir", "srcDirOpt", "source Rune directory", false);
        Option srcFileOpt = createOption("f", "file", "srcFileOpt", "source Rune file", false);
        Option tgtDirOpt  = createOption("t", "tgt", "tgtDirOpt", "target Python directory", false);
        options.addOption(help);
        options.addOption(srcDirOpt);
        options.addOption(srcFileOpt);
        options.addOption(tgtDirOpt);
        CommandLineParser parser = new DefaultParser();
        try {
            // parse the command cmd arguments
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("h")) {
                System.out.println(help.getDescription());
            } else if (!cmd.hasOption("s") && !cmd.hasOption("f")) {
                System.err.println("either a source director or source file must be specified");
                System.err.println(help.getDescription());
            } else {
                String tgtDir = (cmd.hasOption('t')) ? cmd.getOptionValue("t") : ".";
                if (cmd.hasOption("s")) {
                    // use the source directory to generate Python
                    String srcDir = cmd.getOptionValue("s");
                    translateRuneFromSourceDir(srcDir, tgtDir);
                } else {
                    // use the input file to generate Python
                    String srcFile = cmd.getOptionValue("f");
                    translateRuneFromSourceFile(srcFile, tgtDir);
                }
            }
        } catch (MissingOptionException e) {
            System.err.println("Missing required option(s): " + e.getMissingOptions());
            System.err.println(help.getDescription());
        } catch (ParseException e) {
            System.err.println("Failed to parse command line arguments");
        }
    }
    /**
     * generate command line options
     *
     * @param shortName - option short name
     * @param longName - option long name
     * @param argName - option argument name
     * @param description - option description (used in help)
     * @param required - is option required
     * @return - returns Option
     */
    private static Option createOption(String shortName,
                                       String longName,
                                       String argName,
                                       String description,
                                       boolean required) {
        // creates input options
        return Option.builder(shortName)
                .longOpt(longName)
                .argName(argName)
                .desc(description)
                .hasArg()
                .required(required)
                .build();
    }

    /**
     *
     * generate Python from all Rosetta files found in the srdDir and write to tgtDir
     * @param srcDir - Rune source directory
     * @param tgtDir - Python target directory
     */
    private static void translateRuneFromSourceDir(String srcDir, String tgtDir) {
        System.out.println("RunePythonTranslator::main ... reading from: " + srcDir + " and writing to: " + tgtDir);
        Path srcDirPath = Path.of(srcDir);
        if (!Files.exists(srcDirPath)) {
            System.err.println("Rune source directory does not exist: " + srcDir);
        } else if (!Files.isDirectory(srcDirPath)) {
            System.err.println("Rune source directory is not a directory: " + srcDir);
        } else {
            List<Path> rosetta = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(srcDirPath)) {
                paths.filter(Files::isRegularFile)  // Only consider files (not directories)
                        .filter(f -> f.getFileName().toString().endsWith(".rosetta"))
                        .forEach(rosetta::add);
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("RunePythonTranslator ... read directory " + srcDir + " # files found: " + rosetta.size());
            translateRune(rosetta, tgtDir);
        }
    }

    /**
     * generate Python from source file and write to tgtDir
     *
     * @param srcFile - Rune source file
     * @param tgtDir - Python target output directory
     */
    private static void translateRuneFromSourceFile (String srcFile, String tgtDir) {
        System.out.println("RunePythonTranslator::main ... reading from: " + srcFile + " and writing to: " + tgtDir);
        Path srcFilePath = Path.of(srcFile);
        if (!Files.exists(srcFilePath)) {
            System.err.println("Rune source file does not exist: " + srcFile);
        } else if (Files.isDirectory(srcFilePath)) {
            System.err.println("Rune source file is a directory: " + srcFile);
        } else {
            List<Path> rosetta = new ArrayList<>();
            rosetta.add(srcFilePath);
            System.out.println("RunePythonTranslator ... found " + srcFile);
            translateRune(rosetta, tgtDir);
        }
    }

    /**
     * generate Python from a list of files (rosetta) and write the results to tgtDir
     *
     * @param rosetta - list of rune files
     * @param tgtDir - target Python output directory
     */
    private static void translateRune(List<Path> rosetta, String tgtDir) {
        System.out.print("RunePythonTranslator ... initializing ... injecting standalone setup");
        Injector injector = new org.finos.rune.python.RunePythonTranslator.PythonRosettaStandaloneSetup().createInjectorAndDoEMFRegistration();
        System.out.print("... injecting generator");
        PythonCodeGenerator pythonCodeGenerator = injector.getInstance(PythonCodeGenerator.class);
        System.out.print("... getting instance of model loader");
        org.finos.rune.python.RunePythonTranslator.PythonModelLoader pythonModelLoader = injector.getInstance(org.finos.rune.python.RunePythonTranslator.PythonModelLoader.class);
        System.out.print("... getting standard inputs");
        List<Path> staticRosettaFilePaths = ClassPathUtils.findStaticRosettaFilePaths();
        List<RosettaModel> generatorInputs = pythonModelLoader.rosettaModels(staticRosettaFilePaths, rosetta);
        System.out.println("... start");
        final HashMap<String, CharSequence> generatedPython = CollectionLiterals.newHashMap();
        ResourceSet resourceSet = pythonModelLoader.getResourceSetProvider().get();
        boolean firstPass = true;
        String version = null;
        for (RosettaModel model : generatorInputs) {
            version = "";//model.getVersion();
            System.out.println("RunePythonTranslator ... generate: " + model.getName() + " " + version);
            if (firstPass) {
                pythonCodeGenerator.beforeAllGenerate(resourceSet, generatorInputs, version);
                firstPass = false;
            }
            generatedPython.putAll(pythonCodeGenerator.beforeAllGenerate(resourceSet,
                    Collections.unmodifiableSet(CollectionLiterals.newHashSet(model)),
                    version));
            generatedPython.putAll(pythonCodeGenerator.beforeGenerate(model.eResource(),
                    model,
                    version));
            generatedPython.putAll(pythonCodeGenerator.generate(model.eResource(),
                    model,
                    version));
            generatedPython.putAll(pythonCodeGenerator.afterGenerate(model.eResource(),
                    model,
                    version));
        }
        generatedPython.putAll(pythonCodeGenerator.afterAllGenerate(resourceSet, generatorInputs, version));
        System.out.println("RunePythonTranslator ... # of generated files: " + generatedPython.size());
        writePythonFiles(generatedPython, tgtDir);
    }

    /**
     * write generated Python to tgtDir
     *
     * @param generatedPython - collection of output Python files
     * @param tgtDir - target directory
     */
    static private void writePythonFiles (@NotNull HashMap<String, CharSequence> generatedPython, String tgtDir) {
        for (String filePath: generatedPython.keySet()) {
            try {
                CharSequence value = generatedPython.get(filePath);
                final String fileContents = value.toString();
                final Path outputPath = Path.of(tgtDir, File.separator, filePath);
                System.out.println("RunePythonTranslator ... writing file : " + outputPath);
                Files.createDirectories(outputPath.getParent());
                Files.write(outputPath, fileContents.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.out.println("RunePythonTranslator ... caught exception: " + e);
                e.printStackTrace();
            }
        }
    }

    static class PythonModelLoader {
        @Inject
        Provider<ResourceSet> resourceSetProvider;

        public List<RosettaModel> rosettaModels(List<Path> statics, List<Path> rosetta) {
            ResourceSet resourceSet = this.resourceSetProvider.get();
            return Stream.concat(statics.stream(),
                            rosetta.stream())
                    .map(UrlUtils::toUrl)
                    .map(org.finos.rune.python.RunePythonTranslator.PythonModelLoader::url)
                    .map((f) -> getResource(resourceSet, f))
                    .filter(Objects::nonNull)
                    .map(Resource::getContents)
                    .flatMap(Collection::stream)
                    .map((r) -> (RosettaModel) r)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        public Provider<ResourceSet> getResourceSetProvider() {
            return resourceSetProvider;
        }

        private static String url(URL c) {
            try {
                return c.toURI().toURL().toURI().toASCIIString();
            } catch (URISyntaxException | MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        private static Resource getResource(ResourceSet resourceSet, String f) {
            try {
                return resourceSet.getResource(URI.createURI(f, true), true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class PythonRosettaRuntimeModule extends RosettaRuntimeModule {

        PythonRosettaRuntimeModule () {
            super();
        }
        @Override
        public void configure(Binder binder) {
            super.configure(binder);
            binder.bind(org.finos.rune.python.RunePythonTranslator.PythonModelLoader.class);
        }

        @Override
        public Class<? extends Provider<ExternalGenerators>> provideExternalGenerators() {
            return org.finos.rune.python.RunePythonTranslator.PythonPythonCodeGenerator.class;
        }
    }

    static class PythonPythonCodeGenerator implements Provider<ExternalGenerators> {
        @Inject
        PythonCodeGenerator pythonCodeGenerator;

        @Override
        public ExternalGenerators get() {
            return new ExternalGenerators() {
                @NotNull
                @Override
                public Iterator<ExternalGenerator> iterator() {
                    return List.<ExternalGenerator>of(pythonCodeGenerator).iterator();
                }
            };
        }
    }

    static class PythonRosettaStandaloneSetup extends RosettaStandaloneSetup {
        @Override
        public Injector createInjector() {
            return Guice.createInjector(new org.finos.rune.python.RunePythonTranslator.PythonRosettaRuntimeModule());
        }
    }
}
