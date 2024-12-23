package org.finos.rune.python;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.List;

import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.Resource;
import com.regnosys.rosetta.RosettaRuntimeModule;
import org.eclipse.emf.common.util.URI;
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
    public static void main(String[] args) throws IOException {
        System.out.println("RunePythonTranslator ... injecting standalone setup");
        try {
            Injector injector = new org.finos.rune.python.RunePythonTranslator.PythonRosettaStandaloneSetup().createInjectorAndDoEMFRegistration();
            System.out.println("RunePythonTranslator ... injecting generator");
            PythonCodeGenerator pythonCodeGenerator = injector.getInstance(PythonCodeGenerator.class);
            System.out.println("RunePythonTranslator ... getting instance of model loader");
            org.finos.rune.python.RunePythonTranslator.PythonModelLoader pythonModelLoader = injector.getInstance(org.finos.rune.python.RunePythonTranslator.PythonModelLoader.class);

            Path inDir = Path.of("../../cdm-rosetta-versions/5_19_0");
            Path outDir = Path.of("./temp");
            List<Path> rosetta = new ArrayList<>();

            try (Stream<Path> paths = Files.list(inDir)) {
                paths.filter(f -> f.getFileName().toString().endsWith(".rosetta"))
                        .forEach(rosetta::add);
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("reading directory " + inDir + " # files found: " + rosetta.size());
        /*
        List<Path> rosetta = Files.list(inDir)
                .filter(f -> f.getFileName().toString().endsWith(".rosetta"))
                .toList();
         */

            List<Path> staticRosettaFilePaths = ClassPathUtils.findStaticRosettaFilePaths();

            List<RosettaModel> generatorInputs = pythonModelLoader.rosettaModels(staticRosettaFilePaths, rosetta);

            pythonCodeGenerator.beforeAllGenerate(pythonModelLoader.getResourceSetProvider().get(), generatorInputs, "");

/*
        List<? extends Map<String, ? extends CharSequence>> list = generatorInputs.stream()
                .map(model -> pythonCodeGenerator.generate(model.eResource(), model, model.getVersion())).toList();
 */
            List<Map<String, ? extends CharSequence>> list = new ArrayList<>();
            for (RosettaModel model : generatorInputs) {
                Map<String, ? extends CharSequence> generate = pythonCodeGenerator.generate(model.eResource(), model, model.getVersion());
                list.add(generate);
            }

            pythonCodeGenerator.afterAllGenerate(pythonModelLoader.getResourceSetProvider().get(), generatorInputs, "");

            for (Map<String, ? extends CharSequence> generatedCode : list) {
                for (Map.Entry<String, ? extends CharSequence> entry : generatedCode.entrySet()) {
                    Path outFile = Files.createDirectories(outDir.resolve(entry.getKey()));
                    Files.writeString(outFile, String.valueOf(entry.getValue()));
                }
            }
        }
        catch (Exception e) {
            System.out.println("RunePythonTranslator ... caught exception: " + e);
            e.printStackTrace();
        }
    }

    static class PythonModelLoader {
        @Inject
        Provider<ResourceSet> resourceSetProvider;

        public List<RosettaModel> rosettaModels(List<Path> statics, List<Path> rosetta) {
            ResourceSet resourceSet = this.resourceSetProvider.get();
            return Stream.concat(statics.stream(), rosetta.stream())
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
