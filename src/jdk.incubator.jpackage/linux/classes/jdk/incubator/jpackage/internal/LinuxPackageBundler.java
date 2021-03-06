/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.incubator.jpackage.internal;

import java.io.*;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static jdk.incubator.jpackage.internal.DesktopIntegration.*;
import static jdk.incubator.jpackage.internal.LinuxAppBundler.LINUX_INSTALL_DIR;
import static jdk.incubator.jpackage.internal.LinuxAppBundler.LINUX_PACKAGE_DEPENDENCIES;
import static jdk.incubator.jpackage.internal.StandardBundlerParam.*;


abstract class LinuxPackageBundler extends AbstractBundler {

    LinuxPackageBundler(BundlerParamInfo<String> packageName) {
        this.packageName = packageName;
    }

    @Override
    final public boolean validate(Map<String, ? super Object> params)
            throws ConfigException {

        // run basic validation to ensure requirements are met
        // we are not interested in return code, only possible exception
        APP_BUNDLER.fetchFrom(params).validate(params);

        validateInstallDir(LINUX_INSTALL_DIR.fetchFrom(params));

        validateFileAssociations(FILE_ASSOCIATIONS.fetchFrom(params));

        // If package name has some restrictions, the string converter will
        // throw an exception if invalid
        packageName.getStringConverter().apply(packageName.fetchFrom(params),
            params);

        for (var validator: getToolValidators(params)) {
            ConfigException ex = validator.validate();
            if (ex != null) {
                throw ex;
            }
        }

        withFindNeededPackages = LibProvidersLookup.supported();
        if (!withFindNeededPackages) {
            final String advice;
            if ("deb".equals(getID())) {
                advice = "message.deb-ldd-not-available.advice";
            } else {
                advice = "message.rpm-ldd-not-available.advice";
            }
            // Let user know package dependencies will not be generated.
            Log.error(String.format("%s\n%s", I18N.getString(
                    "message.ldd-not-available"), I18N.getString(advice)));
        }

        // Packaging specific validation
        doValidate(params);

        return true;
    }

    @Override
    final public String getBundleType() {
        return "INSTALLER";
    }

    @Override
    final public File execute(Map<String, ? super Object> params,
            File outputParentDir) throws PackagerException {
        IOUtils.writableOutputDir(outputParentDir.toPath());

        PlatformPackage thePackage = createMetaPackage(params);

        Function<File, ApplicationLayout> initAppImageLayout = imageRoot -> {
            ApplicationLayout layout = appImageLayout(params);
            layout.pathGroup().setPath(new Object(),
                    AppImageFile.getPathInAppImage(Path.of("")));
            return layout.resolveAt(imageRoot.toPath());
        };

        try {
            File appImage = StandardBundlerParam.getPredefinedAppImage(params);

            // we either have an application image or need to build one
            if (appImage != null) {
                initAppImageLayout.apply(appImage).copy(
                        thePackage.sourceApplicationLayout());
            } else {
                appImage = APP_BUNDLER.fetchFrom(params).doBundle(params,
                        thePackage.sourceRoot().toFile(), true);
                ApplicationLayout srcAppLayout = initAppImageLayout.apply(
                        appImage);
                if (appImage.equals(PREDEFINED_RUNTIME_IMAGE.fetchFrom(params))) {
                    // Application image points to run-time image.
                    // Copy it.
                    srcAppLayout.copy(thePackage.sourceApplicationLayout());
                } else {
                    // Application image is a newly created directory tree.
                    // Move it.
                    srcAppLayout.move(thePackage.sourceApplicationLayout());
                    if (appImage.exists()) {
                        // Empty app image directory might remain after all application
                        // directories have been moved.
                        appImage.delete();
                    }
                }
            }

            desktopIntegration = DesktopIntegration.create(thePackage, params);

            Map<String, String> data = createDefaultReplacementData(params);
            if (desktopIntegration != null) {
                data.putAll(desktopIntegration.create());
            } else {
                Stream.of(DESKTOP_COMMANDS_INSTALL, DESKTOP_COMMANDS_UNINSTALL,
                        UTILITY_SCRIPTS).forEach(v -> data.put(v, ""));
            }

            data.putAll(createReplacementData(params));

            File packageBundle = buildPackageBundle(Collections.unmodifiableMap(
                    data), params, outputParentDir);

            verifyOutputBundle(params, packageBundle.toPath()).stream()
                    .filter(Objects::nonNull)
                    .forEachOrdered(ex -> {
                Log.verbose(ex.getLocalizedMessage());
                Log.verbose(ex.getAdvice());
            });

            return packageBundle;
        } catch (IOException ex) {
            Log.verbose(ex);
            throw new PackagerException(ex);
        }
    }

    private List<String> getListOfNeededPackages(
            Map<String, ? super Object> params) throws IOException {

        PlatformPackage thePackage = createMetaPackage(params);

        final List<String> xdgUtilsPackage;
        if (desktopIntegration != null) {
            xdgUtilsPackage = desktopIntegration.requiredPackages();
        } else {
            xdgUtilsPackage = Collections.emptyList();
        }

        final List<String> neededLibPackages;
        if (withFindNeededPackages) {
            LibProvidersLookup lookup = new LibProvidersLookup();
            initLibProvidersLookup(params, lookup);

            neededLibPackages = lookup.execute(thePackage.sourceRoot());
        } else {
            neededLibPackages = Collections.emptyList();
        }

        // Merge all package lists together.
        // Filter out empty names, sort and remove duplicates.
        List<String> result = Stream.of(xdgUtilsPackage, neededLibPackages).flatMap(
                List::stream).filter(Predicate.not(String::isEmpty)).sorted().distinct().collect(
                Collectors.toList());

        Log.verbose(String.format("Required packages: %s", result));

        return result;
    }

    private Map<String, String> createDefaultReplacementData(
            Map<String, ? super Object> params) throws IOException {
        Map<String, String> data = new HashMap<>();

        data.put("APPLICATION_PACKAGE", createMetaPackage(params).name());
        data.put("APPLICATION_VENDOR", VENDOR.fetchFrom(params));
        data.put("APPLICATION_VERSION", VERSION.fetchFrom(params));
        data.put("APPLICATION_DESCRIPTION", DESCRIPTION.fetchFrom(params));
        data.put("APPLICATION_RELEASE", RELEASE.fetchFrom(params));

        String defaultDeps = String.join(", ", getListOfNeededPackages(params));
        String customDeps = LINUX_PACKAGE_DEPENDENCIES.fetchFrom(params).strip();
        if (!customDeps.isEmpty() && !defaultDeps.isEmpty()) {
            customDeps = ", " + customDeps;
        }
        data.put("PACKAGE_DEFAULT_DEPENDENCIES", defaultDeps);
        data.put("PACKAGE_CUSTOM_DEPENDENCIES", customDeps);

        return data;
    }

    abstract protected List<ConfigException> verifyOutputBundle(
            Map<String, ? super Object> params, Path packageBundle);

    abstract protected void initLibProvidersLookup(
            Map<String, ? super Object> params,
            LibProvidersLookup libProvidersLookup);

    abstract protected List<ToolValidator> getToolValidators(
            Map<String, ? super Object> params);

    abstract protected void doValidate(Map<String, ? super Object> params)
            throws ConfigException;

    abstract protected Map<String, String> createReplacementData(
            Map<String, ? super Object> params) throws IOException;

    abstract protected File buildPackageBundle(
            Map<String, String> replacementData,
            Map<String, ? super Object> params, File outputParentDir) throws
            PackagerException, IOException;

    final protected PlatformPackage createMetaPackage(
            Map<String, ? super Object> params) {
        return new PlatformPackage() {
            @Override
            public String name() {
                return packageName.fetchFrom(params);
            }

            @Override
            public Path sourceRoot() {
                return IMAGES_ROOT.fetchFrom(params).toPath().toAbsolutePath();
            }

            @Override
            public ApplicationLayout sourceApplicationLayout() {
                return appImageLayout(params).resolveAt(
                        applicationInstallDir(sourceRoot()));
            }

            @Override
            public ApplicationLayout installedApplicationLayout() {
                return appImageLayout(params).resolveAt(
                        applicationInstallDir(Path.of("/")));
            }

            private Path applicationInstallDir(Path root) {
                Path installDir = Path.of(LINUX_INSTALL_DIR.fetchFrom(params),
                        name());
                if (installDir.isAbsolute()) {
                    installDir = Path.of("." + installDir.toString()).normalize();
                }
                return root.resolve(installDir);
            }
        };
    }

    private ApplicationLayout appImageLayout(
            Map<String, ? super Object> params) {
        if (StandardBundlerParam.isRuntimeInstaller(params)) {
            return ApplicationLayout.javaRuntime();
        }
        return ApplicationLayout.linuxAppImage();
    }

    private static void validateInstallDir(String installDir) throws
            ConfigException {
        if (installDir.startsWith("/usr/") || installDir.equals("/usr")) {
            throw new ConfigException(MessageFormat.format(I18N.getString(
                    "error.unsupported-install-dir"), installDir), null);
        }

        if (installDir.isEmpty()) {
            throw new ConfigException(MessageFormat.format(I18N.getString(
                    "error.invalid-install-dir"), "/"), null);
        }

        boolean valid = false;
        try {
            final Path installDirPath = Path.of(installDir);
            valid = installDirPath.isAbsolute();
            if (valid && !installDirPath.normalize().toString().equals(
                    installDirPath.toString())) {
                // Don't allow '/opt/foo/..' or /opt/.
                valid = false;
            }
        } catch (InvalidPathException ex) {
        }

        if (!valid) {
            throw new ConfigException(MessageFormat.format(I18N.getString(
                    "error.invalid-install-dir"), installDir), null);
        }
    }

    private static void validateFileAssociations(
            List<Map<String, ? super Object>> associations) throws
            ConfigException {
        // only one mime type per association, at least one file extention
        int assocIdx = 0;
        for (var assoc : associations) {
            ++assocIdx;
            List<String> mimes = FA_CONTENT_TYPE.fetchFrom(assoc);
            if (mimes == null || mimes.isEmpty()) {
                String msgKey = "error.no-content-types-for-file-association";
                throw new ConfigException(
                        MessageFormat.format(I18N.getString(msgKey), assocIdx),
                        I18N.getString(msgKey + ".advise"));

            }

            if (mimes.size() > 1) {
                String msgKey = "error.too-many-content-types-for-file-association";
                throw new ConfigException(
                        MessageFormat.format(I18N.getString(msgKey), assocIdx),
                        I18N.getString(msgKey + ".advise"));
            }
        }
    }

    private final BundlerParamInfo<String> packageName;
    private boolean withFindNeededPackages;
    private DesktopIntegration desktopIntegration;

    private static final BundlerParamInfo<LinuxAppBundler> APP_BUNDLER =
        new StandardBundlerParam<>(
                "linux.app.bundler",
                LinuxAppBundler.class,
                (params) -> new LinuxAppBundler(),
                null
        );

}
