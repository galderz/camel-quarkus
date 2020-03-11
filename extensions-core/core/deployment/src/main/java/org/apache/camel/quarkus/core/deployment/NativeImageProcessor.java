/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.quarkus.core.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveMethodBuildItem;
import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.Consumer;
import org.apache.camel.Converter;
import org.apache.camel.Endpoint;
import org.apache.camel.Producer;
import org.apache.camel.TypeConverter;
import org.apache.camel.impl.engine.DefaultComponentResolver;
import org.apache.camel.impl.engine.DefaultDataFormatResolver;
import org.apache.camel.impl.engine.DefaultLanguageResolver;
import org.apache.camel.quarkus.core.CamelConfig;
import org.apache.camel.quarkus.core.Flags;
import org.apache.camel.quarkus.core.deployment.util.PathFilter;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.spi.ExchangeFormatter;
import org.apache.camel.spi.PropertiesComponent;
import org.apache.camel.spi.ScheduledPollConsumerScheduler;
import org.apache.camel.spi.StreamCachingStrategy;
import org.apache.camel.support.CamelContextHelper;
import org.jboss.jandex.AnnotationTarget.Kind;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.ClassUtils.getPackageName;

class NativeImageProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeImageProcessor.class);

    /*
     * NativeImage configuration steps related to camel core.
     */
    public static class Core {
        private static final List<Class<?>> CAMEL_REFLECTIVE_CLASSES = Arrays.asList(
                Endpoint.class,
                Consumer.class,
                Producer.class,
                TypeConverter.class,
                ExchangeFormatter.class,
                ScheduledPollConsumerScheduler.class,
                Component.class,
                CamelContext.class,
                StreamCachingStrategy.class,
                StreamCachingStrategy.SpoolUsedHeapMemoryLimit.class,
                PropertiesComponent.class,
                DataFormat.class);

        @BuildStep
        void reflectiveItems(
                CombinedIndexBuildItem combinedIndex,
                BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
                BuildProducer<ReflectiveMethodBuildItem> reflectiveMethod) {

            final IndexView view = combinedIndex.getIndex();

            CAMEL_REFLECTIVE_CLASSES.stream()
                    .map(Class::getName)
                    .map(DotName::createSimple)
                    .map(view::getAllKnownImplementors)
                    .flatMap(Collection::stream)
                    .filter(CamelSupport::isPublic)
                    .forEach(v -> reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, v.name().toString())));

            DotName converter = DotName.createSimple(Converter.class.getName());
            List<ClassInfo> converterClasses = view.getAnnotations(converter)
                    .stream()
                    .filter(ai -> ai.target().kind() == Kind.CLASS)
                    .filter(ai -> {
                        AnnotationValue av = ai.value("loader");
                        boolean isLoader = av != null && av.asBoolean();
                        // filter out camel-base converters which are automatically inlined in the
                        // CoreStaticTypeConverterLoader
                        // need to revisit with Camel 3.0.0-M3 which should improve this area
                        if (ai.target().asClass().name().toString().startsWith("org.apache.camel.converter.")) {
                            LOGGER.debug("Ignoring core " + ai + " " + ai.target().asClass().name());
                            return false;
                        } else if (isLoader) {
                            LOGGER.debug("Ignoring " + ai + " " + ai.target().asClass().name());
                            return false;
                        } else {
                            LOGGER.debug("Accepting " + ai + " " + ai.target().asClass().name());
                            return true;
                        }
                    })
                    .map(ai -> ai.target().asClass())
                    .collect(Collectors.toList());

            LOGGER.debug("Converter classes: " + converterClasses);
            converterClasses
                    .forEach(ci -> reflectiveClass.produce(new ReflectiveClassBuildItem(false, false, ci.name().toString())));

            view.getAnnotations(converter)
                    .stream()
                    .filter(ai -> ai.target().kind() == Kind.METHOD)
                    .filter(ai -> converterClasses.contains(ai.target().asMethod().declaringClass()))
                    .map(ai -> ai.target().asMethod())
                    .forEach(mi -> reflectiveMethod.produce(new ReflectiveMethodBuildItem(mi)));

        }

        @BuildStep
        void camelServices(
                List<CamelServiceBuildItem> camelServices,
                BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {

            camelServices.stream()
                    .forEach(service -> {
                        reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, service.type));
                    });

        }

        /*
         * Add camel catalog files to the native image.
         */
        @BuildStep(onlyIf = Flags.RuntimeCatalogEnabled.class)
        List<NativeImageResourceBuildItem> camelRuntimeCatalog(
                CamelConfig config,
                ApplicationArchivesBuildItem archives,
                List<CamelServicePatternBuildItem> servicePatterns) {

            List<NativeImageResourceBuildItem> resources = new ArrayList<>();

            final PathFilter pathFilter = servicePatterns.stream()
                    .collect(
                            PathFilter.Builder::new,
                            (builder, patterns) -> builder.patterns(patterns.isInclude(), patterns.getPatterns()),
                            PathFilter.Builder::combine)
                    .build();

            CamelSupport.services(archives, pathFilter)
                    .filter(service -> service.name != null && service.type != null && service.path != null)
                    .forEach(service -> {

                        String packageName = getPackageName(service.type);
                        String jsonPath = String.format("%s/%s.json", packageName.replace('.', '/'), service.name);

                        if (config.runtimeCatalog.components
                                && service.path.startsWith(DefaultComponentResolver.RESOURCE_PATH)) {
                            resources.add(new NativeImageResourceBuildItem(jsonPath));
                        }
                        if (config.runtimeCatalog.dataformats
                                && service.path.startsWith(DefaultDataFormatResolver.DATAFORMAT_RESOURCE_PATH)) {
                            resources.add(new NativeImageResourceBuildItem(jsonPath));
                        }
                        if (config.runtimeCatalog.languages
                                && service.path.startsWith(DefaultLanguageResolver.LANGUAGE_RESOURCE_PATH)) {
                            resources.add(new NativeImageResourceBuildItem(jsonPath));
                        }
                    });

            if (config.runtimeCatalog.models) {
                for (ApplicationArchive archive : archives.getAllApplicationArchives()) {
                    final Path root = archive.getArchiveRoot();
                    final Path resourcePath = root.resolve(CamelContextHelper.MODEL_DOCUMENTATION_PREFIX);

                    if (!Files.isDirectory(resourcePath)) {
                        continue;
                    }

                    List<String> items = CamelSupport.safeWalk(resourcePath)
                            .filter(Files::isRegularFile)
                            .map(root::relativize)
                            .map(Path::toString)
                            .collect(Collectors.toList());

                    LOGGER.debug("Register catalog json: {}", items);
                    resources.add(new NativeImageResourceBuildItem(items));
                }
            }

            return resources;
        }
    }

    /*
     * NativeImage configuration steps related to camel main that are activated by default but can be
     * disabled by setting quarkus.camel.main.enabled = false
     */
    public static class Main {
        @BuildStep(onlyIf = Flags.MainEnabled.class)
        void process(
                List<CamelRoutesBuilderClassBuildItem> camelRoutesBuilders,
                BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {

            //
            // Register routes as reflection aware as camel-main main use reflection
            // to bind beans to the registry
            //
            camelRoutesBuilders.forEach(camelRoutesBuilderClassBuildItem -> {
                reflectiveClass.produce(
                        new ReflectiveClassBuildItem(true, true, camelRoutesBuilderClassBuildItem.getDotName().toString()));
            });

            reflectiveClass.produce(new ReflectiveClassBuildItem(
                    true,
                    false,
                    org.apache.camel.main.DefaultConfigurationProperties.class,
                    org.apache.camel.main.MainConfigurationProperties.class,
                    org.apache.camel.main.HystrixConfigurationProperties.class,
                    org.apache.camel.main.RestConfigurationProperties.class));
        }
    }
}
