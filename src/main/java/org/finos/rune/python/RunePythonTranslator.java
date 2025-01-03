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

public class RunePythonTranslator {

    //  mvn compile exec:java -Dexec.mainClass="org.finos.rune.python.RunePythonTranslator"
    /**
     * Add this to yur pom.xml
     * <dependencies>
     * <dependency>
     * <groupId>com.regnosys</groupId>
     * <artifactId>rosetta-common</artifactId>
     * <version>${rosetta.common.version}</version>
     * </dependency>
     * <dependency>
     * <groupId>com.regnosys.rosetta</groupId>
     * <artifactId>com.regnosys.rosetta</artifactId>
     * <version>${rosetta.dsl.version}</version>
     * </dependency>
     * <dependency>
     * <groupId>com.regnosys.rosetta.code-generators</groupId>
     * <artifactId>python</artifactId>
     * <version>${rosetta.code-gen.version}</version>
     * </dependency>
     * </dependencies>
     * *
     */
    private static Option createOption(String shortName,
                                       String longName,
                                       String argName,
                                       String description,
                                       boolean required) {
        return Option.builder(shortName)
                .longOpt(longName)
                .argName(argName)
                .desc(description)
                .hasArg()
                .required(required)
                .build();
    }

    public static void main(String[] args) {
        Options options = new Options();
        Option help     = new Option("h", "usage:\n\tRunePythonGenerator --src [source file] --tgt [target directory ... defaults to .]");
        Option srcDir   = createOption("s", "file", "sourceDir", "source Rune directory", false);
        Option tgtDir   = createOption("t", "tgt", "targetDirectory", "output directory", false);
        options.addOption(help);
        options.addOption(srcDir);
        options.addOption(tgtDir);
        CommandLineParser parser = new DefaultParser();
        try {
            // parse the command cmd arguments
            String srcDirName = "../../cdm/cdm-rosetta-versions/5_19_0";
            String tgtDirName = "./temp";
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("h") || !cmd.hasOption("t")) {
                System.out.println(help.getDescription());
            } else {
                if (cmd.hasOption("s")) {
                    srcDirName = cmd.getOptionValue("s");
                }
                if (cmd.hasOption("t")) {
                    tgtDirName = cmd.getOptionValue("t");
                }
                // integrate generator code here
                // test that the file exists, read the file, invoke the generator, write the results to the target directory
                System.out.println("RunePythonInterpreter::main ... source directory: " + srcDirName);
                System.out.println("RunePythonInterpreter::main ... target directory: " + tgtDirName);
            }
            System.out.println("RunePythonTranslator ... injecting standalone setup");
            Injector injector = new org.finos.rune.python.RunePythonTranslator.PythonRosettaStandaloneSetup().createInjectorAndDoEMFRegistration();
            System.out.println("RunePythonTranslator ... injecting generator");
            PythonCodeGenerator pythonCodeGenerator = injector.getInstance(PythonCodeGenerator.class);
            System.out.println("RunePythonTranslator ... getting instance of model loader");
            org.finos.rune.python.RunePythonTranslator.PythonModelLoader pythonModelLoader = injector.getInstance(org.finos.rune.python.RunePythonTranslator.PythonModelLoader.class);

            List<Path> rosetta = new ArrayList<>();
            Path srcDirPath = Path.of(srcDirName);

            try (Stream<Path> paths = Files.list(srcDirPath)) {
                paths.filter(f -> f.getFileName().toString().endsWith(".rosetta"))
                        .forEach(rosetta::add);
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("RunePythonTranslator ... read directory " + srcDirName + " # files found: " + rosetta.size());
            List<Path> staticRosettaFilePaths = ClassPathUtils.findStaticRosettaFilePaths();
            List<RosettaModel> generatorInputs = pythonModelLoader.rosettaModels(staticRosettaFilePaths, rosetta);
            final HashMap<String, CharSequence> generatedPython = CollectionLiterals.newHashMap();
            System.out.println("RunePythonTranslator ... generate start");
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
            System.out.println("RunePythonTranslator ... afterAll");
            generatedPython.putAll(pythonCodeGenerator.afterAllGenerate(resourceSet, generatorInputs, version));
            System.out.println("RunePythonTranslator ... # of generated files: " + generatedPython.size());
            writePythonFiles(generatedPython, tgtDirName);
        } catch (ParseException e) {
        }
        catch (Exception e) {
            System.out.println("RunePythonTranslator ... caught exception: " + e);
            e.printStackTrace();
        }
    }
    static void writePythonFiles (HashMap<String, CharSequence> generatedPython, String tgtDirName) {
        for (String filePath: generatedPython.keySet()) {
            try {
                CharSequence value = generatedPython.get(filePath);
                final String fileContents = value.toString();
                System.out.println("RunePythonTranslator ... writing file : " + filePath);
                final Path outputPath = Path.of(tgtDirName, File.separator, filePath);
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
            System.out.println("PythonRosettaRuntimeModule ... constructor");
        }
        @Override
        public void configure(Binder binder) {
            System.out.println("PythonRosettaRuntimeModule ... configure");
            super.configure(binder);
            binder.bind(org.finos.rune.python.RunePythonTranslator.PythonModelLoader.class);
        }

        @Override
        public Class<? extends Provider<ExternalGenerators>> provideExternalGenerators() {
            System.out.println("PythonRosettaRuntimeModule ... provideExternalGenerators");
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
            System.out.println("PythonRosettaStandaloneSetup ... createInjector");
            return Guice.createInjector(new org.finos.rune.python.RunePythonTranslator.PythonRosettaRuntimeModule());
        }
    }
}
